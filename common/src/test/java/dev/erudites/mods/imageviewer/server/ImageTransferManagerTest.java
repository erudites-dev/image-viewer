package dev.erudites.mods.imageviewer.server;

import dev.erudites.mods.imageviewer.network.ImageHashes;
import dev.erudites.mods.imageviewer.network.payload.ImageDataPayload;
import dev.erudites.mods.imageviewer.network.payload.ImageErrorPayload;
import dev.erudites.mods.imageviewer.network.payload.ImageRequestPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageTransferManagerTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final int MAX_BYTES = 64 * 1024 * 1024;
    private static final int CHUNK = ImageDataPayload.MAX_CHUNK_SIZE;

    private record Sent(CustomPacketPayload payload, Runnable onWritten) {}

    private record Scheduled(Runnable task, long dueNanos) {}

    @TempDir
    Path directory;

    private final List<Sent> sent = new ArrayList<>();
    private final ArrayDeque<Sent> unwritten = new ArrayDeque<>();
    private final List<Scheduled> scheduled = new ArrayList<>();
    private long now;
    private int seed;

    @Test
    void streamsWholeFileInOrderedChunks() throws IOException {
        byte[] content = this.randomBytes(CHUNK * 2 + 1234);
        String hash = this.writeImage("1.png", content);
        ImageTransferManager manager = this.manager(0);

        manager.request(PLAYER, this::send, List.of(new ImageRequestPayload.Entry(hash, 1)));
        this.drain();

        assertArrayEquals(content, this.reassemble(hash, 1));
        assertEquals(0, manager.trackedPlayerCount());
        assertEquals(0, manager.openFileCount());
    }

    @Test
    void limitsBytesInFlightUntilWritesComplete() throws IOException {
        byte[] content = this.randomBytes(ImageTransferManager.MAX_IN_FLIGHT_BYTES * 2);
        String hash = this.writeImage("1.png", content);
        ImageTransferManager manager = this.manager(0);

        manager.request(PLAYER, this::send, List.of(new ImageRequestPayload.Entry(hash, 1)));

        assertEquals(ImageTransferManager.MAX_IN_FLIGHT_BYTES, this.dataBytes());
        this.writeOne();
        this.runScheduled();
        assertEquals(ImageTransferManager.MAX_IN_FLIGHT_BYTES + CHUNK, this.dataBytes());

        this.drain();
        assertArrayEquals(content, this.reassemble(hash, 1));
        assertEquals(0, manager.trackedPlayerCount());
    }

    @Test
    void sendsInRequestedPriorityOrder() throws IOException {
        byte[] first = this.randomBytes(1000);
        byte[] second = this.randomBytes(2000);
        String firstHash = this.writeImage("1.png", first);
        String secondHash = this.writeImage("2.png", second);
        ImageTransferManager manager = this.manager(0);

        manager.request(PLAYER, this::send, List.of(
            new ImageRequestPayload.Entry(secondHash, 2),
            new ImageRequestPayload.Entry(firstHash, 1)
        ));
        this.drain();

        List<String> order = this.sent.stream().map(item -> ((ImageDataPayload) item.payload()).hash()).toList();
        assertEquals(List.of(secondHash, firstHash), order);
    }

    @Test
    void resumesPreemptedTransferWithSameTicket() throws IOException {
        byte[] large = this.randomBytes(ImageTransferManager.MAX_IN_FLIGHT_BYTES + CHUNK * 3);
        byte[] small = this.randomBytes(1000);
        String largeHash = this.writeImage("1.png", large);
        String smallHash = this.writeImage("2.png", small);
        ImageTransferManager manager = this.manager(0);

        manager.request(PLAYER, this::send, List.of(new ImageRequestPayload.Entry(largeHash, 1)));
        manager.request(PLAYER, this::send, List.of(
            new ImageRequestPayload.Entry(smallHash, 2),
            new ImageRequestPayload.Entry(largeHash, 1)
        ));
        this.drain();

        assertArrayEquals(small, this.reassemble(smallHash, 2));
        assertArrayEquals(large, this.reassemble(largeHash, 1));
        assertEquals(0, manager.trackedPlayerCount());
        assertEquals(0, manager.openFileCount());
    }

    @Test
    void restartsTransferWhenTicketChanges() throws IOException {
        byte[] content = this.randomBytes(ImageTransferManager.MAX_IN_FLIGHT_BYTES + CHUNK);
        String hash = this.writeImage("1.png", content);
        ImageTransferManager manager = this.manager(0);

        manager.request(PLAYER, this::send, List.of(new ImageRequestPayload.Entry(hash, 1)));
        manager.request(PLAYER, this::send, List.of(new ImageRequestPayload.Entry(hash, 2)));
        this.drain();

        assertArrayEquals(content, this.reassemble(hash, 2));
    }

    @Test
    void dropsTransfersMissingFromNewRequestAndReleasesFile() throws IOException {
        byte[] content = this.randomBytes(ImageTransferManager.MAX_IN_FLIGHT_BYTES + CHUNK);
        String hash = this.writeImage("1.png", content);
        ImageTransferManager manager = this.manager(0);

        manager.request(PLAYER, this::send, List.of(new ImageRequestPayload.Entry(hash, 1)));
        assertEquals(1, manager.openFileCount());
        manager.request(PLAYER, this::send, List.of());
        int sentBefore = this.dataBytes();
        this.drain();

        assertEquals(sentBefore, this.dataBytes());
        assertEquals(0, manager.trackedPlayerCount());
        assertEquals(0, manager.openFileCount());
    }

    @Test
    void reportsUnknownHash() {
        ImageTransferManager manager = this.manager(0);
        String unknown = "0".repeat(ImageHashes.HEX_LENGTH);

        manager.request(PLAYER, this::send, List.of(new ImageRequestPayload.Entry(unknown, 5)));

        assertEquals(List.of(new ImageErrorPayload(unknown, 5, ImageErrorPayload.Reason.NOT_FOUND)), this.payloads());
        assertEquals(0, manager.trackedPlayerCount());
    }

    @Test
    void ignoresDuplicateEntries() throws IOException {
        String hash = this.writeImage("1.png", this.randomBytes(1000));
        ImageTransferManager manager = this.manager(0);

        manager.request(PLAYER, this::send, List.of(
            new ImageRequestPayload.Entry(hash, 1),
            new ImageRequestPayload.Entry(hash, 1)
        ));
        this.drain();

        assertEquals(1, this.sent.size());
    }

    @Test
    void enforcesUploadRateLimit() throws IOException {
        byte[] content = this.randomBytes(CHUNK * 4);
        String hash = this.writeImage("1.png", content);
        long bytesPerSecond = CHUNK;
        ImageTransferManager manager = this.manager(bytesPerSecond);

        manager.request(PLAYER, this::send, List.of(new ImageRequestPayload.Entry(hash, 1)));
        this.flushWrites();
        assertEquals(0, this.dataBytes());
        assertEquals(1, this.scheduled.size());

        this.advance(TimeUnit.SECONDS.toNanos(1));
        this.flushWrites();
        assertEquals(CHUNK, this.dataBytes());

        this.advance(TimeUnit.SECONDS.toNanos(3));
        this.drain();
        assertArrayEquals(content, this.reassemble(hash, 1));
    }

    @Test
    void releasesFileWhenPlayerRemovedMidTransfer() throws IOException {
        String hash = this.writeImage("1.png", this.randomBytes(ImageTransferManager.MAX_IN_FLIGHT_BYTES + CHUNK));
        ImageTransferManager manager = this.manager(0);

        manager.request(PLAYER, this::send, List.of(new ImageRequestPayload.Entry(hash, 1)));
        assertEquals(1, manager.openFileCount());
        manager.remove(PLAYER);
        this.drain();

        assertEquals(0, manager.trackedPlayerCount());
        assertEquals(0, manager.openFileCount());
    }

    @Test
    void releasesFilesOnClose() throws IOException {
        String first = this.writeImage("1.png", this.randomBytes(ImageTransferManager.MAX_IN_FLIGHT_BYTES + CHUNK));
        String second = this.writeImage("2.png", this.randomBytes(ImageTransferManager.MAX_IN_FLIGHT_BYTES + CHUNK));
        ImageTransferManager manager = this.manager(0);

        manager.request(PLAYER, this::send, List.of(new ImageRequestPayload.Entry(first, 1)));
        manager.request(UUID.randomUUID(), this::send, List.of(new ImageRequestPayload.Entry(second, 1)));
        assertEquals(2, manager.openFileCount());
        manager.close();
        this.drain();

        assertEquals(0, manager.trackedPlayerCount());
        assertEquals(0, manager.openFileCount());
    }

    @Test
    void dropsTransfersForImagesRemovedFromCatalog() throws IOException {
        String hash = this.writeImage("1.png", this.randomBytes(ImageTransferManager.MAX_IN_FLIGHT_BYTES + CHUNK));
        ImageTransferManager manager = this.manager(0);
        manager.request(PLAYER, this::send, List.of(new ImageRequestPayload.Entry(hash, 1)));

        Files.delete(this.directory.resolve("1.png"));
        manager.update(ImageCatalog.scan(this.directory, MAX_BYTES, ImageCatalog.empty()), 0);
        this.drain();

        assertEquals(0, manager.trackedPlayerCount());
        assertEquals(0, manager.openFileCount());
    }

    @Test
    void reportsFileChangedAfterScan() throws IOException {
        String hash = this.writeImage("1.png", this.randomBytes(1000));
        ImageTransferManager manager = this.manager(0);
        Files.write(this.directory.resolve("1.png"), this.randomBytes(10));

        manager.request(PLAYER, this::send, List.of(new ImageRequestPayload.Entry(hash, 4)));

        assertEquals(List.of(new ImageErrorPayload(hash, 4, ImageErrorPayload.Reason.IO_ERROR)), this.payloads());
        assertEquals(0, manager.trackedPlayerCount());
    }

    @Test
    void dropsPlayerWhenSendFails() throws IOException {
        String hash = this.writeImage("1.png", this.randomBytes(CHUNK * 2));
        ImageTransferManager manager = this.manager(0);

        manager.request(PLAYER, (payload, onWritten) -> {
            throw new IllegalStateException("connection closed");
        }, List.of(new ImageRequestPayload.Entry(hash, 1)));

        assertEquals(0, manager.trackedPlayerCount());
        assertEquals(0, manager.openFileCount());
    }

    private ImageTransferManager manager(final long bytesPerSecond) {
        return new ImageTransferManager(
            ImageCatalog.scan(this.directory, MAX_BYTES, ImageCatalog.empty()),
            bytesPerSecond,
            () -> this.now,
            (task, delayNanos) -> this.scheduled.add(new Scheduled(task, this.now + delayNanos))
        );
    }

    private void send(final CustomPacketPayload payload, final Runnable onWritten) {
        Sent item = new Sent(payload, onWritten);
        this.sent.add(item);
        this.unwritten.add(item);
    }

    private void writeOne() {
        Sent item = this.unwritten.poll();
        if (item != null) {
            item.onWritten().run();
        }
    }

    private void runScheduled() {
        boolean ran = true;
        while (ran) {
            ran = false;
            for (Scheduled item : List.copyOf(this.scheduled)) {
                if (item.dueNanos() <= this.now) {
                    this.scheduled.remove(item);
                    item.task().run();
                    ran = true;
                }
            }
        }
    }

    private void flushWrites() {
        while (!this.unwritten.isEmpty()) {
            this.writeOne();
            this.runScheduled();
        }
        this.runScheduled();
    }

    private void advance(final long nanos) {
        this.now += nanos;
        this.runScheduled();
    }

    private void drain() {
        for (int i = 0; i < 10_000 && (!this.unwritten.isEmpty() || !this.scheduled.isEmpty()); i++) {
            this.flushWrites();
            if (!this.scheduled.isEmpty()) {
                this.advance(this.scheduled.stream().mapToLong(Scheduled::dueNanos).min().orElse(this.now) - this.now);
            }
        }
        assertTrue(this.unwritten.isEmpty());
        assertTrue(this.scheduled.isEmpty());
    }

    private List<CustomPacketPayload> payloads() {
        return this.sent.stream().map(Sent::payload).toList();
    }

    private int dataBytes() {
        int total = 0;
        for (Sent item : this.sent) {
            if (item.payload() instanceof ImageDataPayload data) {
                total += data.data().length;
            }
        }
        return total;
    }

    private byte[] reassemble(final String hash, final int ticket) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Sent item : this.sent) {
            if (item.payload() instanceof ImageDataPayload data && data.hash().equals(hash) && data.ticket() == ticket) {
                assertEquals(out.size(), data.offset());
                assertTrue(data.data().length <= CHUNK);
                out.writeBytes(data.data());
            }
        }
        return out.toByteArray();
    }

    private String writeImage(final String name, final byte[] content) throws IOException {
        Files.write(this.directory.resolve(name), content);
        return ImageHashes.sha256(content);
    }

    private byte[] randomBytes(final int length) {
        byte[] bytes = new byte[length];
        new Random(this.seed++).nextBytes(bytes);
        return bytes;
    }
}
