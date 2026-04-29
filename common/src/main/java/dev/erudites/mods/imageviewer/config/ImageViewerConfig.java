package dev.erudites.mods.imageviewer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.erudites.mods.imageviewer.ImageViewer;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class ImageViewerConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path ROOT = Path.of(ImageViewer.MODID);
    private static final Path CONFIG_PATH = ROOT.resolve("config.json");
    private static final Path IMAGES_DIR = ROOT.resolve("images");
    private static final Path README_PATH = ROOT.resolve("README.md");
    private static final String README_RESOURCE = "/assets/imageviewer/README.md";

    /**
     * Port for the image web server.
     * Set to 0 to use a random available port (not recommended for production;
     * the port must be reachable by clients so it should be opened in the firewall).
     * Default: 25580
     */
    public int webServerPort = 25580;

    private static ImageViewerConfig instance;

    private ImageViewerConfig() {}

    public static ImageViewerConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static ImageViewerConfig load() {
        ensureLayout();
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                ImageViewerConfig config = GSON.fromJson(reader, ImageViewerConfig.class);
                if (config != null) {
                    config.save();
                    return config;
                }
            } catch (IOException e) {
                ImageViewer.LOGGER.error("Failed to load imageviewer config, using defaults", e);
            }
        }
        ImageViewerConfig config = new ImageViewerConfig();
        config.save();
        return config;
    }

    private static void ensureLayout() {
        try {
            Files.createDirectories(IMAGES_DIR);
        } catch (IOException e) {
            ImageViewer.LOGGER.error("Failed to create imageviewer/images directory", e);
        }
        if (!Files.exists(README_PATH)) {
            try (InputStream in = ImageViewerConfig.class.getResourceAsStream(README_RESOURCE)) {
                if (in == null) {
                    ImageViewer.LOGGER.error("Bundled README resource not found at {}", README_RESOURCE);
                } else {
                    Files.createDirectories(README_PATH.getParent());
                    Files.copy(in, README_PATH, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                ImageViewer.LOGGER.error("Failed to write imageviewer README", e);
            }
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            ImageViewer.LOGGER.error("Failed to save imageviewer config", e);
        }
    }
}
