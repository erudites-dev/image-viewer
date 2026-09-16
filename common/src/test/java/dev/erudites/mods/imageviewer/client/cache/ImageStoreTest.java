package dev.erudites.mods.imageviewer.client.cache;

import dev.erudites.mods.imageviewer.network.ImageHashes;
import dev.erudites.mods.imageviewer.network.payload.CatalogPayload;
import dev.erudites.mods.imageviewer.network.payload.ImageDataPayload;
import dev.erudites.mods.imageviewer.network.payload.ImageErrorPayload;
import dev.erudites.mods.imageviewer.network.payload.ImageRequestPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageStoreTest {

    private static final Executor DIRECT = Runnable::run;
    private static final long MEMORY_BUDGET = 1024;

    @TempDir
    Path directory;

    private final List<List<ImageRequestPayload.Entry>> requests = new ArrayList<>();
    private int seed;

    @Test
    void downloadsVerifiesAndCachesImage() throws Exception {
        byte[] content = this.randomBytes(700);
        CatalogPayload.Entry entry = this.entry(content);
        ImageStore store = this.store();

        CompletableFuture<byte[]> future = store.fetch(entry);
        int ticket = this.ticket(entry);

        store.onData(new ImageDataPayload(entry.hash(), ticket, 0, content.length, Arrays.copyOfRange(content, 0, 300)));
        assertEquals(new ImageStore.Progress(300, 700), store.progress(entry.hash()));
        store.onData(new ImageDataPayload(entry.hash(), ticket, 300, content.length, Arrays.copyOfRange(content, 300, 700)));

        assertArrayEquals(content, future.get());
        assertEquals(0, store.pendingCount());
        assertNull(store.progress(entry.hash()));
        assertArrayEquals(content, new DiskImageCache(this.directory, Long.MAX_VALUE).read(entry.hash(), content.length));
    }

    @Test
    void servesRepeatedFetchFromMemory() throws Exception {
        byte[] content = this.randomBytes(100);
        CatalogPayload.Entry entry = this.entry(content);
        ImageStore store = this.store();
        this.download(store, content);

        byte[] first = store.fetch(entry).get();
        byte[] second = store.fetch(entry).get();

        assertSame(first, second);
        assertEquals(1, this.requests.size());
    }

    @Test
    void servesFromDiskWithoutNetwork() throws Exception {
        byte[] content = this.randomBytes(100);
        CatalogPayload.Entry entry = this.entry(content);
        new DiskImageCache(this.directory, Long.MAX_VALUE).write(entry.hash(), content);

        assertArrayEquals(content, this.store().fetch(entry).get());
        assertTrue(this.requests.isEmpty());
    }

    @Test
    void sharesInFlightDownloadBetweenCallers() throws Exception {
        byte[] content = this.randomBytes(100);
        CatalogPayload.Entry entry = this.entry(content);
        ImageStore store = this.store();

        CompletableFuture<byte[]> first = store.fetch(entry);
        CompletableFuture<byte[]> second = store.fetch(entry);
        first.cancel(false);
        store.onData(new ImageDataPayload(entry.hash(), this.ticket(entry), 0, content.length, content));

        assertEquals(1, this.requests.size());
        assertArrayEquals(content, second.get());
    }

    @Test
    void requestsImagesInPriorityOrder() {
        byte[] current = this.randomBytes(100);
        byte[] next = this.randomBytes(100);
        byte[] later = this.randomBytes(100);
        ImageStore store = this.store();

        store.prioritize(List.of(this.entry(current), this.entry(next), this.entry(later)));

        assertEquals(
            List.of(ImageHashes.sha256(current), ImageHashes.sha256(next), ImageHashes.sha256(later)),
            this.lastRequest().stream().map(ImageRequestPayload.Entry::hash).toList()
        );
    }

    @Test
    void reordersAndCancelsWhenPriorityChanges() {
        byte[] first = this.randomBytes(100);
        byte[] second = this.randomBytes(100);
        byte[] third = this.randomBytes(100);
        ImageStore store = this.store();
        store.prioritize(List.of(this.entry(first), this.entry(second), this.entry(third)));
        List<ImageRequestPayload.Entry> initial = this.lastRequest();

        CompletableFuture<byte[]> dropped = store.fetch(this.entry(first));
        store.prioritize(List.of(this.entry(third), this.entry(second)));

        assertEquals(List.of(initial.get(2), initial.get(1)), this.lastRequest());
        assertTrue(dropped.isCompletedExceptionally());
        assertEquals(2, store.pendingCount());
    }

    @Test
    void emptyPriorityCancelsEverythingAndNotifiesServer() {
        ImageStore store = this.store();
        store.prioritize(List.of(this.entry(this.randomBytes(100)), this.entry(this.randomBytes(100))));

        store.prioritize(List.of());

        assertEquals(0, store.pendingCount());
        assertEquals(List.of(), this.lastRequest());
    }

    @Test
    void prefetchedImagesGoToDiskButNotMemory() throws Exception {
        byte[] content = this.randomBytes(100);
        CatalogPayload.Entry entry = this.entry(content);
        ImageStore store = this.store();

        store.prioritize(List.of(entry));
        store.onData(new ImageDataPayload(entry.hash(), this.ticket(entry), 0, content.length, content));

        assertEquals(0, store.memoryBytes());
        int requestsBefore = this.requests.size();
        assertArrayEquals(content, store.fetch(entry).get());
        assertEquals(requestsBefore, this.requests.size());
    }

    @Test
    void ignoresChunksFromStaleTicket() throws Exception {
        byte[] content = this.randomBytes(100);
        CatalogPayload.Entry entry = this.entry(content);
        ImageStore store = this.store();

        CompletableFuture<byte[]> future = store.fetch(entry);
        int ticket = this.ticket(entry);
        store.onData(new ImageDataPayload(entry.hash(), ticket + 100, 50, content.length, Arrays.copyOfRange(content, 50, 100)));
        store.onError(new ImageErrorPayload(entry.hash(), ticket + 100, ImageErrorPayload.Reason.IO_ERROR));
        store.onData(new ImageDataPayload(entry.hash(), ticket, 0, content.length, content));

        assertArrayEquals(content, future.get());
    }

    @Test
    void usesNewTicketAfterRestart() {
        byte[] content = this.randomBytes(100);
        CatalogPayload.Entry entry = this.entry(content);
        ImageStore store = this.store();

        store.prioritize(List.of(entry));
        int firstTicket = this.ticket(entry);
        store.prioritize(List.of());
        store.prioritize(List.of(entry));

        assertTrue(this.ticket(entry) != firstTicket);
    }

    @Test
    void failsOnHashMismatch() {
        byte[] content = this.randomBytes(100);
        CatalogPayload.Entry entry = this.entry(content);
        ImageStore store = this.store();

        CompletableFuture<byte[]> future = store.fetch(entry);
        store.onData(new ImageDataPayload(entry.hash(), this.ticket(entry), 0, content.length, this.randomBytes(100)));

        assertThrows(ExecutionException.class, future::get);
        assertEquals(0, store.pendingCount());
    }

    @Test
    void failsOnOutOfOrderChunk() {
        byte[] content = this.randomBytes(100);
        CatalogPayload.Entry entry = this.entry(content);
        ImageStore store = this.store();

        CompletableFuture<byte[]> future = store.fetch(entry);
        store.onData(new ImageDataPayload(entry.hash(), this.ticket(entry), 50, content.length, Arrays.copyOfRange(content, 50, 100)));

        assertThrows(ExecutionException.class, future::get);
        assertEquals(0, store.pendingCount());
    }

    @Test
    void failsWhenServerAnnouncesDifferentSize() {
        byte[] content = this.randomBytes(100);
        CatalogPayload.Entry entry = this.entry(content);
        ImageStore store = this.store();

        CompletableFuture<byte[]> future = store.fetch(entry);
        store.onData(new ImageDataPayload(entry.hash(), this.ticket(entry), 0, Integer.MAX_VALUE, content));

        assertThrows(ExecutionException.class, future::get);
        assertEquals(0, store.pendingCount());
    }

    @Test
    void failsOnChunkOverflowingExpectedSize() {
        byte[] content = this.randomBytes(100);
        CatalogPayload.Entry entry = this.entry(content);
        ImageStore store = this.store();

        CompletableFuture<byte[]> future = store.fetch(entry);
        store.onData(new ImageDataPayload(entry.hash(), this.ticket(entry), 0, content.length, this.randomBytes(150)));

        assertThrows(ExecutionException.class, future::get);
    }

    @Test
    void failsOnServerError() {
        byte[] content = this.randomBytes(100);
        CatalogPayload.Entry entry = this.entry(content);
        ImageStore store = this.store();

        CompletableFuture<byte[]> future = store.fetch(entry);
        store.onError(new ImageErrorPayload(entry.hash(), this.ticket(entry), ImageErrorPayload.Reason.NOT_FOUND));

        assertThrows(ExecutionException.class, future::get);
        assertEquals(0, store.pendingCount());
    }

    @Test
    void timesOutWhenServerStopsSending() {
        byte[] content = this.randomBytes(100);
        CatalogPayload.Entry entry = this.entry(content);
        ImageStore store = this.store();

        CompletableFuture<byte[]> future = store.fetch(entry);
        store.onData(new ImageDataPayload(entry.hash(), this.ticket(entry), 0, content.length, Arrays.copyOfRange(content, 0, 10)));
        for (int i = 0; i < ImageStore.TRANSFER_TIMEOUT_TICKS; i++) {
            store.tick();
        }
        assertEquals(1, store.pendingCount());
        store.tick();

        assertThrows(ExecutionException.class, future::get);
        assertEquals(0, store.pendingCount());
    }

    @Test
    void queuedImagesDoNotTimeOutWhileOthersAreTransferring() throws Exception {
        byte[] current = this.randomBytes(100);
        byte[] queued = this.randomBytes(100);
        ImageStore store = this.store();
        store.prioritize(List.of(this.entry(current), this.entry(queued)));
        CompletableFuture<byte[]> queuedFuture = store.fetch(this.entry(queued));
        int ticket = this.ticket(this.entry(current));

        for (int i = 0; i < ImageStore.TRANSFER_TIMEOUT_TICKS * 2; i++) {
            store.tick();
            if (i % 100 == 0 && i / 100 < current.length) {
                store.onData(new ImageDataPayload(ImageHashes.sha256(current), ticket, i / 100, current.length, new byte[] {current[i / 100]}));
            }
        }

        assertFalse(queuedFuture.isDone());
    }

    @Test
    void abortNetworkFailsOnlyNetworkRequests() {
        byte[] content = this.randomBytes(100);
        ImageStore store = this.store();
        CompletableFuture<byte[]> future = store.fetch(this.entry(content));

        store.abortNetwork(new IOException("send failed"));

        assertThrows(ExecutionException.class, future::get);
        assertEquals(0, store.pendingCount());
    }

    @Test
    void cancelsImagesMissingFromNewCatalog() {
        byte[] kept = this.randomBytes(100);
        byte[] dropped = this.randomBytes(200);
        ImageStore store = this.store();

        CompletableFuture<byte[]> keptFuture = store.fetch(this.entry(kept));
        CompletableFuture<byte[]> droppedFuture = store.fetch(this.entry(dropped));
        store.retainOnly(Set.of(ImageHashes.sha256(kept)));

        assertEquals(1, store.pendingCount());
        assertTrue(droppedFuture.isCompletedExceptionally());
        assertFalse(keptFuture.isDone());
    }

    @Test
    void clearReleasesEverything() throws Exception {
        byte[] cached = this.randomBytes(100);
        byte[] pending = this.randomBytes(200);
        ImageStore store = this.store();
        this.download(store, cached);
        CatalogPayload.Entry pendingEntry = this.entry(pending);
        CompletableFuture<byte[]> future = store.fetch(pendingEntry);
        store.onData(new ImageDataPayload(pendingEntry.hash(), this.ticket(pendingEntry), 0, pending.length, Arrays.copyOfRange(pending, 0, 50)));

        store.clear();

        assertEquals(0, store.pendingCount());
        assertEquals(0, store.memoryBytes());
        ExecutionException error = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(CancellationException.class, error.getCause());
    }

    @Test
    void ignoresChunksAfterClear() {
        byte[] content = this.randomBytes(100);
        CatalogPayload.Entry entry = this.entry(content);
        ImageStore store = this.store();
        store.fetch(entry);
        int ticket = this.ticket(entry);
        store.clear();

        store.onData(new ImageDataPayload(entry.hash(), ticket, 0, content.length, content));

        assertEquals(0, store.pendingCount());
        assertEquals(0, store.memoryBytes());
    }

    @Test
    void evictsLeastRecentlyUsedBytesBeyondBudget() throws Exception {
        ImageStore store = this.store(0);
        byte[] first = this.randomBytes(600);
        byte[] second = this.randomBytes(600);
        this.download(store, first);
        this.download(store, second);

        assertEquals(600, store.memoryBytes());
        assertTrue(store.fetch(this.entry(second)).isDone());
        assertFalse(store.fetch(this.entry(first)).isDone());
    }

    @Test
    void skipsMemoryCacheForImagesLargerThanBudget() throws Exception {
        ImageStore store = this.store();
        this.download(store, this.randomBytes((int) MEMORY_BUDGET + 1));

        assertEquals(0, store.memoryBytes());
    }

    @Test
    void rejectsInvalidEntries() {
        ImageStore store = this.store();

        assertTrue(store.fetch(new CatalogPayload.Entry("../escape", 10)).isCompletedExceptionally());
        assertTrue(store.fetch(new CatalogPayload.Entry("0".repeat(64), 0)).isCompletedExceptionally());
        assertTrue(store.fetch(new CatalogPayload.Entry("0".repeat(64), ImageStore.MAX_IMAGE_BYTES + 1)).isCompletedExceptionally());
        assertEquals(0, store.pendingCount());
        assertTrue(this.requests.isEmpty());
    }

    private void download(final ImageStore store, final byte[] content) throws Exception {
        CatalogPayload.Entry entry = this.entry(content);
        CompletableFuture<byte[]> future = store.fetch(entry);
        store.onData(new ImageDataPayload(entry.hash(), this.ticket(entry), 0, content.length, content));
        future.get();
    }

    private int ticket(final CatalogPayload.Entry entry) {
        return this.lastRequest().stream()
            .filter(request -> request.hash().equals(entry.hash()))
            .findFirst()
            .orElseThrow()
            .ticket();
    }

    private List<ImageRequestPayload.Entry> lastRequest() {
        return this.requests.getLast();
    }

    private ImageStore store() {
        return this.store(Long.MAX_VALUE);
    }

    private ImageStore store(final long diskBytes) {
        return new ImageStore(DIRECT, DIRECT, new DiskImageCache(this.directory, diskBytes), this.requests::add, MEMORY_BUDGET);
    }

    private CatalogPayload.Entry entry(final byte[] content) {
        return new CatalogPayload.Entry(ImageHashes.sha256(content), content.length);
    }

    private byte[] randomBytes(final int length) {
        byte[] bytes = new byte[length];
        new Random(this.seed++).nextBytes(bytes);
        return bytes;
    }
}
