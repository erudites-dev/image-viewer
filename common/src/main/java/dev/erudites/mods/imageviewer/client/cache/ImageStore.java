package dev.erudites.mods.imageviewer.client.cache;

import dev.erudites.mods.imageviewer.ImageViewer;
import dev.erudites.mods.imageviewer.network.ImageHashes;
import dev.erudites.mods.imageviewer.network.payload.CatalogPayload;
import dev.erudites.mods.imageviewer.network.payload.ImageDataPayload;
import dev.erudites.mods.imageviewer.network.payload.ImageErrorPayload;
import dev.erudites.mods.imageviewer.network.payload.ImageRequestPayload;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class ImageStore {

    public static final int MAX_IMAGE_BYTES = 128 * 1024 * 1024;
    public static final int TRANSFER_TIMEOUT_TICKS = 20 * 30;
    public static final boolean LOG_TIMINGS = Boolean.getBoolean("imageviewer.debugTimings");

    private static final byte[] EMPTY = new byte[0];

    public record Progress(int receivedBytes, int totalBytes) {}

    private enum Stage {
        DISK,
        NETWORK,
        VERIFYING
    }

    private static final class Pending {
        private final CatalogPayload.Entry entry;
        private final CompletableFuture<byte[]> future = new CompletableFuture<>();
        private final long createdNanos = System.nanoTime();
        private Stage stage = Stage.DISK;
        private boolean keepInMemory;
        private int ticket;
        private byte @Nullable [] buffer;
        private int received;
        private long networkStartNanos;
        private long firstByteNanos;
        private long lastByteNanos;

        private Pending(final CatalogPayload.Entry entry) {
            this.entry = entry;
        }

        private String hash() {
            return this.entry.hash();
        }
    }

    private final Executor mainThread;
    private final Executor io;
    private final DiskImageCache disk;
    private final Consumer<List<ImageRequestPayload.Entry>> requester;
    private final long memoryBudgetBytes;

    private final LinkedHashMap<String, byte[]> memory = new LinkedHashMap<>(16, 0.75F, true);
    private final Map<String, Pending> pending = new HashMap<>();
    private List<String> priority = List.of();
    private List<ImageRequestPayload.Entry> lastRequested = List.of();
    private long memoryBytes;
    private long ticks;
    private long lastNetworkActivityTick;
    private int nextTicket = 1;
    private boolean requestsDirty;

    public ImageStore(
        final Executor mainThread,
        final Executor io,
        final DiskImageCache disk,
        final Consumer<List<ImageRequestPayload.Entry>> requester,
        final long memoryBudgetBytes
    ) {
        this.mainThread = mainThread;
        this.io = io;
        this.disk = disk;
        this.requester = requester;
        this.memoryBudgetBytes = memoryBudgetBytes;
    }

    public CompletableFuture<byte[]> fetch(final CatalogPayload.Entry entry) {
        if (!isAcceptable(entry)) {
            return CompletableFuture.failedFuture(new IOException("Invalid image entry " + entry.hash()));
        }

        byte[] cached = this.memory.get(entry.hash());
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        Pending request = this.pending.get(entry.hash());
        if (request == null) {
            request = this.start(entry);
        }
        request.keepInMemory = true;
        return request.future.copy();
    }

    public void prioritize(final List<CatalogPayload.Entry> entries) {
        List<String> order = new ArrayList<>(entries.size());
        Set<String> wanted = new HashSet<>();
        for (CatalogPayload.Entry entry : entries) {
            if (isAcceptable(entry) && wanted.add(entry.hash())) {
                order.add(entry.hash());
            }
        }
        this.priority = List.copyOf(order);

        List<Pending> unwanted = new ArrayList<>();
        for (Pending request : this.pending.values()) {
            if (request.stage != Stage.VERIFYING && !wanted.contains(request.hash())) {
                unwanted.add(request);
            }
        }
        for (Pending request : unwanted) {
            this.fail(request, new CancellationException("Image no longer wanted"));
        }

        for (CatalogPayload.Entry entry : entries) {
            if (isAcceptable(entry) && !this.memory.containsKey(entry.hash()) && !this.pending.containsKey(entry.hash())) {
                this.start(entry);
            }
        }
        this.markRequestsDirty();
        this.flushRequests();
    }

    public void onData(final ImageDataPayload payload) {
        Pending request = this.pending.get(payload.hash());
        if (request == null || request.stage != Stage.NETWORK || request.ticket != payload.ticket()) {
            return;
        }

        int expectedSize = request.entry.size();
        byte[] data = payload.data();
        if (payload.totalSize() != expectedSize
            || payload.offset() != request.received
            || data.length == 0
            || data.length > expectedSize - request.received) {
            this.fail(request, new IOException("Unexpected chunk for image " + payload.hash()));
            this.flushRequests();
            return;
        }

        long now = System.nanoTime();
        if (request.buffer == null) {
            request.buffer = new byte[expectedSize];
            request.firstByteNanos = now;
        }
        System.arraycopy(data, 0, request.buffer, request.received, data.length);
        request.received += data.length;
        request.lastByteNanos = now;
        this.lastNetworkActivityTick = this.ticks;

        if (request.received == expectedSize) {
            byte[] complete = request.buffer;
            request.buffer = null;
            request.stage = Stage.VERIFYING;
            CompletableFuture
                .supplyAsync(() -> this.verifyAndStore(request.hash(), complete), this.io)
                .whenCompleteAsync((verified, error) -> this.onVerified(request, verified, error), this.mainThread);
        }
    }

    public void onError(final ImageErrorPayload payload) {
        Pending request = this.pending.get(payload.hash());
        if (request != null && request.stage == Stage.NETWORK && request.ticket == payload.ticket()) {
            this.fail(request, new IOException("Server could not send image " + payload.hash() + ": " + payload.reason()));
            this.flushRequests();
        }
    }

    public void abortNetwork(final Throwable cause) {
        List<Pending> network = new ArrayList<>();
        for (Pending request : this.pending.values()) {
            if (request.stage == Stage.NETWORK) {
                network.add(request);
            }
        }
        for (Pending request : network) {
            this.fail(request, cause);
        }
        this.flushRequests();
    }

    public void tick() {
        this.ticks++;
        if (this.hasStage(Stage.NETWORK) && this.ticks - this.lastNetworkActivityTick > TRANSFER_TIMEOUT_TICKS) {
            this.abortNetwork(new IOException("Timed out waiting for image data"));
        }
    }

    public void retainOnly(final Set<String> hashes) {
        List<Pending> removed = new ArrayList<>();
        for (Pending request : this.pending.values()) {
            if (!hashes.contains(request.hash())) {
                removed.add(request);
            }
        }
        for (Pending request : removed) {
            this.fail(request, new CancellationException("Image removed from catalog"));
        }
        this.flushRequests();
    }

    public void clear() {
        for (Pending request : List.copyOf(this.pending.values())) {
            this.fail(request, new CancellationException("Image store cleared"));
        }
        this.memory.clear();
        this.memoryBytes = 0;
        this.priority = List.of();
        this.lastRequested = List.of();
        this.requestsDirty = false;
    }

    public @Nullable Progress progress(final String hash) {
        Pending request = this.pending.get(hash);
        if (request == null) {
            return null;
        }
        return new Progress(request.received, request.entry.size());
    }

    public int pendingCount() {
        return this.pending.size();
    }

    public long memoryBytes() {
        return this.memoryBytes;
    }

    private Pending start(final CatalogPayload.Entry entry) {
        Pending request = new Pending(entry);
        this.pending.put(entry.hash(), request);
        CompletableFuture
            .supplyAsync(() -> this.disk.read(entry.hash(), entry.size()), this.io)
            .exceptionally(t -> EMPTY)
            .thenAcceptAsync(bytes -> this.onDiskResult(request, bytes), this.mainThread);
        return request;
    }

    private void onDiskResult(final Pending request, final byte[] bytes) {
        if (this.pending.get(request.hash()) != request) {
            return;
        }
        if (bytes.length > 0) {
            this.complete(request, bytes);
            if (!this.hasStage(Stage.DISK)) {
                this.flushRequests();
            }
            return;
        }
        if (!this.hasStage(Stage.NETWORK)) {
            this.lastNetworkActivityTick = this.ticks;
        }
        request.stage = Stage.NETWORK;
        request.ticket = this.nextTicket++;
        request.networkStartNanos = System.nanoTime();
        this.markRequestsDirty();
        if (!this.hasStage(Stage.DISK) || (!this.priority.isEmpty() && this.priority.getFirst().equals(request.hash()))) {
            this.flushRequests();
        }
    }

    private byte[] verifyAndStore(final String hash, final byte[] data) {
        if (!ImageHashes.sha256(data).equals(hash)) {
            throw new IllegalStateException("Hash mismatch for image " + hash);
        }
        this.disk.write(hash, data);
        return data;
    }

    private void onVerified(final Pending request, final byte @Nullable [] verified, final @Nullable Throwable error) {
        if (this.pending.get(request.hash()) != request) {
            return;
        }
        if (error != null || verified == null) {
            this.fail(request, error != null ? error : new IOException("Image verification produced no data"));
            this.flushRequests();
        } else {
            this.complete(request, verified);
        }
    }

    private void complete(final Pending request, final byte[] bytes) {
        this.pending.remove(request.hash());
        if (request.keepInMemory) {
            this.remember(request.hash(), bytes);
        }
        if (LOG_TIMINGS) {
            this.logTimings(request, bytes.length);
        }
        request.future.complete(bytes);
    }

    private void fail(final Pending request, final Throwable error) {
        if (!this.pending.remove(request.hash(), request)) {
            return;
        }
        request.buffer = null;
        if (request.stage == Stage.NETWORK) {
            this.markRequestsDirty();
        }
        if (!(error instanceof CancellationException)) {
            ImageViewer.LOGGER.warn("Failed to load image {}: {}", request.hash(), error.getMessage());
        }
        request.future.completeExceptionally(error);
    }

    private boolean hasStage(final Stage stage) {
        for (Pending request : this.pending.values()) {
            if (request.stage == stage) {
                return true;
            }
        }
        return false;
    }

    private void markRequestsDirty() {
        this.requestsDirty = true;
    }

    private void flushRequests() {
        if (!this.requestsDirty) {
            return;
        }
        this.requestsDirty = false;

        List<ImageRequestPayload.Entry> entries = new ArrayList<>();
        Set<String> added = new HashSet<>();
        for (String hash : this.priority) {
            this.appendRequest(entries, added, this.pending.get(hash));
        }
        for (Pending request : this.pending.values()) {
            this.appendRequest(entries, added, request);
        }

        if (entries.equals(this.lastRequested)) {
            return;
        }
        this.lastRequested = List.copyOf(entries);
        this.requester.accept(this.lastRequested);
    }

    private void appendRequest(final List<ImageRequestPayload.Entry> entries, final Set<String> added, final @Nullable Pending request) {
        if (request == null
            || request.stage != Stage.NETWORK
            || entries.size() >= ImageRequestPayload.MAX_ENTRIES
            || !added.add(request.hash())) {
            return;
        }
        entries.add(new ImageRequestPayload.Entry(request.hash(), request.ticket));
    }

    private void remember(final String hash, final byte[] bytes) {
        if (bytes.length > this.memoryBudgetBytes) {
            return;
        }
        byte[] previous = this.memory.put(hash, bytes);
        if (previous != null) {
            this.memoryBytes -= previous.length;
        }
        this.memoryBytes += bytes.length;
        Iterator<byte[]> iterator = this.memory.values().iterator();
        while (this.memoryBytes > this.memoryBudgetBytes && iterator.hasNext()) {
            this.memoryBytes -= iterator.next().length;
            iterator.remove();
        }
    }

    private void logTimings(final Pending request, final int size) {
        long now = System.nanoTime();
        if (request.networkStartNanos == 0) {
            ImageViewer.LOGGER.info(
                "[timings] {} ({} KiB) loaded from disk cache in {} ms",
                request.hash().substring(0, 12),
                size / 1024,
                millis(now - request.createdNanos)
            );
            return;
        }
        long transferNanos = Math.max(1, request.lastByteNanos - request.firstByteNanos);
        ImageViewer.LOGGER.info(
            "[timings] {} ({} KiB) disk miss {} ms, queued {} ms, transfer {} ms ({} KiB/s), verify+store {} ms",
            request.hash().substring(0, 12),
            size / 1024,
            millis(request.networkStartNanos - request.createdNanos),
            millis(request.firstByteNanos - request.networkStartNanos),
            millis(transferNanos),
            size * TimeUnit.SECONDS.toNanos(1) / 1024 / transferNanos,
            millis(now - request.lastByteNanos)
        );
    }

    private static long millis(final long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    private static boolean isAcceptable(final CatalogPayload.Entry entry) {
        return ImageHashes.isValid(entry.hash()) && entry.size() > 0 && entry.size() <= MAX_IMAGE_BYTES;
    }
}
