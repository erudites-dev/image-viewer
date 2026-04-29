package dev.erudites.mods.imageviewer;

import dev.erudites.mods.imageviewer.config.ImageViewerConfig;
import dev.erudites.mods.imageviewer.network.OpenImagePayload;
import dev.erudites.mods.imageviewer.server.ImageWebServer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ImageViewer {

    public static final String MODID = "imageviewer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    private static volatile ImageWebServer webServer;

    private ImageViewer() {}

    public static ImageWebServer startServer() {
        ImageViewerConfig config = ImageViewerConfig.get();
        ImageWebServer server = new ImageWebServer();
        try {
            server.start(config.webServerPort);
            LOGGER.info(
                "Image Viewer web server started on port {} (configured: {})",
                server.getPort(),
                config.webServerPort == 0
                    ? "random"
                    : config.webServerPort
            );
            webServer = server;
            return server;
        } catch (IOException e) {
            LOGGER.error("Failed to start Image Viewer web server on port {}", config.webServerPort, e);
            return null;
        }
    }

    public static void stopServer() {
        ImageWebServer server = webServer;
        if (server != null) {
            server.stop();
            webServer = null;
        }
    }

    public static ImageWebServer webServer() {
        return webServer;
    }

    public static OpenImagePayload buildPayload() {
        ImageWebServer server = webServer;
        if (server == null) {
            return null;
        }
        return new OpenImagePayload(server.getPort(), detectCategories());
    }

    /**
     * Detects image categories from the server's imageviewer/images/ directory.
     * "main" = images directly in imageviewer/images/
     * subdir names = subdirectories containing images
     */
    public static List<String> detectCategories() {
        List<String> categories = new ArrayList<>();
        File imagesDir = new File("imageviewer/images");
        if (!imagesDir.exists()) {
            imagesDir.mkdirs();
        }

        File[] mainImages = imagesDir.listFiles((_, name) -> isImage(name));
        if (mainImages != null && mainImages.length > 0) {
            categories.add("main");
        }

        File[] subdirs = imagesDir.listFiles(File::isDirectory);
        if (subdirs != null) {
            Arrays.sort(subdirs);
            for (File subdir : subdirs) {
                File[] images = subdir.listFiles((_, name) -> isImage(name));
                if (images != null && images.length > 0) {
                    categories.add(subdir.getName());
                }
            }
        }

        return categories;
    }

    private static boolean isImage(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MODID, path);
    }
}
