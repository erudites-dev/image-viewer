package dev.erudites.mods.imageviewer.server;

import dev.erudites.mods.imageviewer.ImageViewer;
import dev.erudites.mods.imageviewer.network.payload.ImageDataPayload;
import dev.erudites.mods.imageviewer.network.payload.ImageErrorPayload;
import dev.erudites.mods.imageviewer.network.payload.ImageRequestPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public final class ImageTransferManager implements AutoCloseable {

    public static final int MAX_IN_FLIGHT_BYTES = 4 * 1024 * 1024;

    private static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final long MAX_REFILL_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final Runnable NO_OP = () -> {};

    @FunctionalInterface
    public interface Link {
        void send(CustomPacketPayload payload, Runnable onWritten);
    }

    @FunctionalInterface
    public interface Scheduler {
        void schedule(Runnable task, long delayNanos);
    }

    private final Map<UUID, PlayerTransfers> players = new HashMap<>();
    private final LongSupplier clock;
    private final Scheduler scheduler;
    private ImageCatalog catalog;
    private long bytesPerSecond;
    private int openFiles;

    public ImageTransferManager(final ImageCatalog catalog, final long bytesPerSecond, final LongSupplier clock, final Scheduler scheduler) {
        this.catalog = catalog;
        this.bytesPerSecond = bytesPerSecond;
        this.clock = clock;
        this.scheduler = scheduler;
    }

    public void update(final ImageCatalog catalog, final long bytesPerSecond) {
        this.catalog = catalog;
        this.bytesPerSecond = bytesPerSecond;
        for (Map.Entry<UUID, PlayerTransfers> entry : List.copyOf(this.players.entrySet())) {
            PlayerTransfers player = entry.getValue();
            player.queue.removeIf(transfer -> {
                ImageCatalog.Image image = catalog.find(transfer.image.hash()).orElse(null);
                if (image == null) {
                    transfer.close();
                    return true;
                }
                transfer.image = image;
                return false;
            });
            player.tokens = 0;
            player.lastRefillNanos = this.clock.getAsLong();
            this.pump(entry.getKey());
            this.removeIfIdle(entry.getKey(), player);
        }
    }

    public void request(final UUID playerId, final Link link, final List<ImageRequestPayload.Entry> entries) {
        PlayerTransfers player = this.players.get(playerId);
        if (player == null) {
            if (entries.isEmpty()) {
                return;
            }
            player = new PlayerTransfers(link, this.clock.getAsLong());
            this.players.put(playerId, player);
        }
        player.link = link;

        Map<String, Transfer> previous = new HashMap<>();
        for (Transfer transfer : player.queue) {
            previous.put(transfer.image.hash(), transfer);
        }

        ArrayDeque<Transfer> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        for (ImageRequestPayload.Entry entry : entries) {
            if (queue.size() >= ImageRequestPayload.MAX_ENTRIES || !seen.add(entry.hash())) {
                continue;
            }
            ImageCatalog.Image image = this.catalog.find(entry.hash()).orElse(null);
            if (image == null) {
                link.send(new ImageErrorPayload(entry.hash(), entry.ticket(), ImageErrorPayload.Reason.NOT_FOUND), NO_OP);
                continue;
            }
            Transfer transfer = previous.remove(entry.hash());
            if (transfer != null && transfer.ticket != entry.ticket()) {
                transfer.close();
                transfer = null;
            }
            queue.add(transfer != null ? transfer : new Transfer(image, entry.ticket()));
        }
        for (Transfer dropped : previous.values()) {
            dropped.close();
        }

        Transfer previousHead = player.queue.peekFirst();
        if (previousHead != null && previousHead != queue.peekFirst()) {
            previousHead.close();
        }
        player.queue = queue;

        this.pump(playerId);
        this.removeIfIdle(playerId, player);
    }

    public void remove(final UUID playerId) {
        PlayerTransfers player = this.players.remove(playerId);
        if (player != null) {
            player.close();
        }
    }

    public int trackedPlayerCount() {
        return this.players.size();
    }

    public int openFileCount() {
        return this.openFiles;
    }

    @Override
    public void close() {
        for (PlayerTransfers player : this.players.values()) {
            player.close();
        }
        this.players.clear();
    }

    private void pump(final UUID playerId) {
        PlayerTransfers player = this.players.get(playerId);
        if (player == null) {
            return;
        }

        while (!player.queue.isEmpty() && player.inFlightBytes < MAX_IN_FLIGHT_BYTES) {
            Transfer transfer = player.queue.peekFirst();
            int length = Math.min(ImageDataPayload.MAX_CHUNK_SIZE, transfer.remaining());

            if (this.bytesPerSecond > 0) {
                this.refill(player);
                if (player.tokens < length) {
                    long missing = length - player.tokens;
                    this.scheduleWake(playerId, player, Math.ceilDiv(missing * NANOS_PER_SECOND, this.bytesPerSecond));
                    return;
                }
            }

            int offset = transfer.offset;
            byte[] chunk;
            try {
                chunk = transfer.read(length);
            } catch (IOException e) {
                ImageViewer.LOGGER.warn("Failed to read image {} for transfer", transfer.image.path(), e);
                player.queue.removeFirst();
                transfer.close();
                player.link.send(new ImageErrorPayload(transfer.image.hash(), transfer.ticket, ImageErrorPayload.Reason.IO_ERROR), NO_OP);
                continue;
            }

            if (this.bytesPerSecond > 0) {
                player.tokens -= length;
            }
            if (transfer.remaining() == 0) {
                player.queue.removeFirst();
                transfer.close();
            }

            player.inFlightBytes += length;
            PlayerTransfers owner = player;
            try {
                player.link.send(
                    new ImageDataPayload(transfer.image.hash(), transfer.ticket, offset, transfer.image.size(), chunk),
                    () -> this.scheduler.schedule(() -> this.onWritten(playerId, owner, length), 0L)
                );
            } catch (RuntimeException e) {
                ImageViewer.LOGGER.warn("Failed to send image chunk, dropping transfers for {}", playerId, e);
                this.remove(playerId);
                return;
            }
        }
    }

    private void onWritten(final UUID playerId, final PlayerTransfers owner, final int length) {
        owner.inFlightBytes -= length;
        if (this.players.get(playerId) != owner) {
            return;
        }
        this.pump(playerId);
        this.removeIfIdle(playerId, owner);
    }

    private void refill(final PlayerTransfers player) {
        long now = this.clock.getAsLong();
        long elapsed = Math.min(now - player.lastRefillNanos, MAX_REFILL_NANOS);
        player.lastRefillNanos = now;
        if (elapsed <= 0) {
            return;
        }
        long capacity = Math.max(this.bytesPerSecond, ImageDataPayload.MAX_CHUNK_SIZE);
        double added = (double) elapsed * this.bytesPerSecond / NANOS_PER_SECOND;
        player.tokens = (long) Math.min(capacity, player.tokens + added);
    }

    private void scheduleWake(final UUID playerId, final PlayerTransfers player, final long delayNanos) {
        if (player.wakeScheduled) {
            return;
        }
        player.wakeScheduled = true;
        this.scheduler.schedule(() -> {
            player.wakeScheduled = false;
            if (this.players.get(playerId) == player) {
                this.pump(playerId);
                this.removeIfIdle(playerId, player);
            }
        }, delayNanos);
    }

    private void removeIfIdle(final UUID playerId, final PlayerTransfers player) {
        if (player.queue.isEmpty() && player.inFlightBytes <= 0 && this.players.get(playerId) == player) {
            this.players.remove(playerId);
        }
    }

    private static final class PlayerTransfers {
        private Link link;
        private ArrayDeque<Transfer> queue = new ArrayDeque<>();
        private long inFlightBytes;
        private long tokens;
        private long lastRefillNanos;
        private boolean wakeScheduled;

        private PlayerTransfers(final Link link, final long now) {
            this.link = link;
            this.lastRefillNanos = now;
        }

        private void close() {
            for (Transfer transfer : this.queue) {
                transfer.close();
            }
            this.queue.clear();
        }
    }

    private final class Transfer {
        private ImageCatalog.Image image;
        private final int ticket;
        private int offset;
        private @Nullable FileChannel channel;

        private Transfer(final ImageCatalog.Image image, final int ticket) {
            this.image = image;
            this.ticket = ticket;
        }

        private int remaining() {
            return this.image.size() - this.offset;
        }

        private byte[] read(final int length) throws IOException {
            if (this.channel == null) {
                FileChannel opened = FileChannel.open(this.image.path(), StandardOpenOption.READ);
                if (opened.size() != this.image.size()) {
                    opened.close();
                    throw new IOException("Image changed on disk since the catalog was built");
                }
                this.channel = opened;
                ImageTransferManager.this.openFiles++;
            }
            byte[] chunk = new byte[length];
            ByteBuffer buffer = ByteBuffer.wrap(chunk);
            while (buffer.hasRemaining()) {
                if (this.channel.read(buffer, (long) this.offset + buffer.position()) < 0) {
                    throw new IOException("Unexpected end of file");
                }
            }
            this.offset += length;
            return chunk;
        }

        private void close() {
            if (this.channel == null) {
                return;
            }
            try {
                this.channel.close();
            } catch (IOException e) {
                ImageViewer.LOGGER.debug("Failed to close image channel {}", this.image.path(), e);
            }
            this.channel = null;
            ImageTransferManager.this.openFiles--;
        }
    }
}
