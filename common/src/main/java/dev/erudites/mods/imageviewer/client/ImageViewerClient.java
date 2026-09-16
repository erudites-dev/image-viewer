package dev.erudites.mods.imageviewer.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.erudites.mods.imageviewer.ImageViewer;
import dev.erudites.mods.imageviewer.client.cache.DiskImageCache;
import dev.erudites.mods.imageviewer.client.cache.ImageStore;
import dev.erudites.mods.imageviewer.client.screen.ImageViewerScreen;
import dev.erudites.mods.imageviewer.client.screen.ImageViewerSelectionScreen;
import dev.erudites.mods.imageviewer.network.ImageHashes;
import dev.erudites.mods.imageviewer.network.payload.CatalogPayload;
import dev.erudites.mods.imageviewer.network.payload.ImageDataPayload;
import dev.erudites.mods.imageviewer.network.payload.ImageErrorPayload;
import dev.erudites.mods.imageviewer.network.payload.ImageRequestPayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class ImageViewerClient {

    private static final long MEMORY_CACHE_BYTES = 64L * 1024 * 1024;
    private static final long DISK_CACHE_BYTES = 512L * 1024 * 1024;
    private static final String DISK_CACHE_DIRECTORY = "imageviewer-cache";

    public static final KeyMapping OPEN_KEY = new KeyMapping(
        "key.imageviewer.open",
        InputConstants.KEY_I,
        KeyMapping.Category.register(ImageViewer.id("imageviewer"))
    );

    private static final ExecutorService IO_EXECUTOR = daemonExecutor("ImageViewer-IO");
    private static final ExecutorService DECODE_EXECUTOR = daemonExecutor("ImageViewer-Decoder");

    private static Consumer<CustomPacketPayload> serverSender = _ -> {};
    private static @Nullable ImageStore store;
    private static @Nullable CatalogPayload catalog;

    private ImageViewerClient() {}

    public static void init(final Consumer<CustomPacketPayload> sender) {
        serverSender = sender;
    }

    public static void onCatalog(final Minecraft minecraft, final CatalogPayload payload) {
        minecraft.execute(() -> {
            catalog = sanitize(payload);
            Set<String> hashes = new HashSet<>();
            for (CatalogPayload.Category category : catalog.categories()) {
                for (CatalogPayload.Entry entry : category.images()) {
                    hashes.add(entry.hash());
                }
            }
            store(minecraft).retainOnly(hashes);
        });
    }

    public static void onData(final Minecraft minecraft, final ImageDataPayload payload) {
        minecraft.execute(() -> {
            if (store != null) {
                store.onData(payload);
            }
        });
    }

    public static void onError(final Minecraft minecraft, final ImageErrorPayload payload) {
        minecraft.execute(() -> {
            if (store != null) {
                store.onError(payload);
            }
        });
    }

    public static void onDisconnect(final Minecraft minecraft) {
        minecraft.execute(() -> {
            catalog = null;
            if (store != null) {
                store.clear();
            }
        });
    }

    public static void tick(final Minecraft minecraft) {
        if (store != null) {
            store.tick();
        }
        while (OPEN_KEY.consumeClick()) {
            if (catalog != null && minecraft.gui.screen() == null) {
                openViewer(minecraft, catalog);
            }
        }
    }

    public static ImageStore store(final Minecraft minecraft) {
        if (store == null) {
            DiskImageCache disk = new DiskImageCache(
                minecraft.gameDirectory.toPath().resolve(DISK_CACHE_DIRECTORY),
                DISK_CACHE_BYTES
            );
            store = new ImageStore(minecraft, IO_EXECUTOR, disk, ImageViewerClient::requestImages, MEMORY_CACHE_BYTES);
        }
        return store;
    }

    public static Executor decodeExecutor() {
        return DECODE_EXECUTOR;
    }

    private static void requestImages(final List<ImageRequestPayload.Entry> entries) {
        try {
            serverSender.accept(new ImageRequestPayload(entries));
        } catch (RuntimeException e) {
            ImageViewer.LOGGER.warn("Failed to request images from the server", e);
            if (store != null && !entries.isEmpty()) {
                store.abortNetwork(e);
            }
        }
    }

    private static void openViewer(final Minecraft minecraft, final CatalogPayload catalog) {
        List<CatalogPayload.Category> categories = catalog.categories();
        if (categories.isEmpty()) {
            return;
        }
        if (categories.size() == 1) {
            minecraft.gui.setScreen(new ImageViewerScreen(categories.getFirst().images(), catalog.keepAspectRatio()));
        } else {
            minecraft.gui.setScreen(new ImageViewerSelectionScreen(categories, catalog.keepAspectRatio()));
        }
    }

    private static CatalogPayload sanitize(final CatalogPayload payload) {
        List<CatalogPayload.Category> categories = payload.categories().stream()
            .map(category -> new CatalogPayload.Category(
                category.name(),
                category.images().stream()
                    .filter(entry -> ImageHashes.isValid(entry.hash()))
                    .filter(entry -> entry.size() > 0 && entry.size() <= ImageStore.MAX_IMAGE_BYTES)
                    .toList()
            ))
            .filter(category -> !category.images().isEmpty())
            .toList();
        return new CatalogPayload(payload.keepAspectRatio(), categories);
    }

    private static ExecutorService daemonExecutor(final String name) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
    }
}
