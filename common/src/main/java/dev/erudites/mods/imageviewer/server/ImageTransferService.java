package dev.erudites.mods.imageviewer.server;

import dev.erudites.mods.imageviewer.ImageViewer;
import dev.erudites.mods.imageviewer.network.payload.ImageRequestPayload;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ImageTransferService implements AutoCloseable {

    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final ScheduledExecutorService executor;
    private final ImageTransferManager manager;

    public ImageTransferService(final ImageCatalog catalog, final long bytesPerSecond) {
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ImageViewer-Transfer");
            thread.setDaemon(true);
            return thread;
        });
        this.manager = new ImageTransferManager(catalog, bytesPerSecond, System::nanoTime, this::schedule);
    }

    public void update(final ImageCatalog catalog, final long bytesPerSecond) {
        this.schedule(() -> this.manager.update(catalog, bytesPerSecond), 0L);
    }

    public void request(final UUID playerId, final ImageTransferManager.Link link, final List<ImageRequestPayload.Entry> entries) {
        List<ImageRequestPayload.Entry> copy = List.copyOf(entries);
        this.schedule(() -> this.manager.request(playerId, link, copy), 0L);
    }

    public void remove(final UUID playerId) {
        this.schedule(() -> this.manager.remove(playerId), 0L);
    }

    @Override
    public void close() {
        this.schedule(this.manager::close, 0L);
        this.executor.shutdown();
        try {
            if (!this.executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                ImageViewer.LOGGER.warn("Image transfer thread did not stop in time");
                this.executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            this.executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void schedule(final Runnable task, final long delayNanos) {
        Runnable guarded = () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                ImageViewer.LOGGER.error("Image transfer task failed", e);
            }
        };
        try {
            if (delayNanos <= 0) {
                this.executor.execute(guarded);
            } else {
                this.executor.schedule(guarded, delayNanos, TimeUnit.NANOSECONDS);
            }
        } catch (RejectedExecutionException e) {
            ImageViewer.LOGGER.debug("Image transfer service is stopped, dropping task");
        }
    }
}
