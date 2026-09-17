package dev.erudites.mods.imageviewer;

import dev.erudites.mods.imageviewer.config.ImageViewerConfig;
import dev.erudites.mods.imageviewer.network.ImageHashes;
import dev.erudites.mods.imageviewer.network.PayloadSender;
import dev.erudites.mods.imageviewer.network.payload.CatalogPayload;
import dev.erudites.mods.imageviewer.network.payload.ImageRequestPayload;
import dev.erudites.mods.imageviewer.server.ImageCatalog;
import dev.erudites.mods.imageviewer.server.ImageTransferService;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

public final class ImageViewer {

    public static final String MODID = "imageviewer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    private static final int NETWORK_VERSION = 1;

    private static @Nullable ImageCatalog catalog;
    private static @Nullable ImageTransferService transfers;

    private ImageViewer() {}

    public static void start() {
        stop();
        ImageViewerConfig config = ImageViewerConfig.reload();
        ImageCatalog scanned = ImageCatalog.scan(ImageViewerConfig.IMAGES_DIR, config.maxImageBytes, ImageCatalog.empty());
        catalog = scanned;
        transfers = new ImageTransferService(scanned, config.maxUploadBytesPerSecond);
        LOGGER.info("Image Viewer started with {} categories", scanned.categories().size());
    }

    public static void stop() {
        ImageTransferService service = transfers;
        transfers = null;
        catalog = null;
        if (service != null) {
            service.close();
        }
    }

    public static boolean isRunning() {
        return catalog != null;
    }

    public static boolean reload() {
        ImageCatalog previous = catalog;
        ImageTransferService service = transfers;
        if (previous == null || service == null) {
            return false;
        }
        ImageViewerConfig config = ImageViewerConfig.reload();
        ImageCatalog scanned = ImageCatalog.scan(ImageViewerConfig.IMAGES_DIR, config.maxImageBytes, previous);
        catalog = scanned;
        service.update(scanned, config.maxUploadBytesPerSecond);
        return true;
    }

    public static @Nullable CatalogPayload catalogPayload() {
        ImageCatalog current = catalog;
        if (current == null) {
            return null;
        }
        return current.toPayload(ImageViewerConfig.get().keepAspectRatio);
    }

    public static void sendCatalog(final ServerPlayer player, final PayloadSender sender) {
        CatalogPayload payload = catalogPayload();
        if (payload != null && sender.canReceive(player)) {
            sender.send(player, payload);
        }
    }

    public static void handleRequest(final ServerPlayer player, final List<ImageRequestPayload.Entry> entries, final PayloadSender sender) {
        ImageTransferService service = transfers;
        if (service == null) {
            return;
        }
        List<ImageRequestPayload.Entry> valid = entries.stream()
            .filter(entry -> ImageHashes.isValid(entry.hash()))
            .toList();
        service.request(player.getUUID(), (payload, onWritten) -> sender.send(player, payload, onWritten), valid);
    }

    public static void onPlayerLeave(final UUID playerId) {
        ImageTransferService service = transfers;
        if (service != null) {
            service.remove(playerId);
        }
    }

    public static Identifier id(final String path) {
        return Identifier.fromNamespaceAndPath(MODID, path);
    }

    public static Identifier payloadId(final String name) {
        return id(name + "_v" + NETWORK_VERSION);
    }
}
