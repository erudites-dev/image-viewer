package dev.erudites.mods.imageviewer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.erudites.mods.imageviewer.ImageViewer;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class ImageViewerConfig {

    public static final int MIN_IMAGE_BYTES = 1024 * 1024;
    public static final int MAX_IMAGE_BYTES = 128 * 1024 * 1024;
    public static final int MIN_UPLOAD_BYTES_PER_SECOND = 64 * 1024;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path ROOT = Path.of(ImageViewer.MODID);
    public static final Path IMAGES_DIR = ROOT.resolve("images");
    private static final Path CONFIG_PATH = ROOT.resolve("config.json");
    private static final Path README_PATH = ROOT.resolve("README.md");
    private static final String README_RESOURCE = "/assets/imageviewer/README.md";

    public int maxImageBytes = 32 * 1024 * 1024;
    public int maxUploadBytesPerSecond = 0;
    public boolean keepAspectRatio = false;

    private static @Nullable ImageViewerConfig instance;

    private ImageViewerConfig() {}

    public static synchronized ImageViewerConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public static synchronized ImageViewerConfig reload() {
        instance = load();
        return instance;
    }

    private static ImageViewerConfig load() {
        ensureLayout();
        ImageViewerConfig config = null;
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                config = GSON.fromJson(reader, ImageViewerConfig.class);
            } catch (IOException | JsonParseException e) {
                ImageViewer.LOGGER.error("Failed to load imageviewer config, using defaults", e);
            }
        }
        if (config == null) {
            config = new ImageViewerConfig();
        }
        config.clamp();
        config.save();
        return config;
    }

    private void clamp() {
        this.maxImageBytes = Math.clamp(this.maxImageBytes, MIN_IMAGE_BYTES, MAX_IMAGE_BYTES);
        this.maxUploadBytesPerSecond = this.maxUploadBytesPerSecond <= 0
            ? 0
            : Math.max(this.maxUploadBytesPerSecond, MIN_UPLOAD_BYTES_PER_SECOND);
    }

    private static void ensureLayout() {
        try {
            Files.createDirectories(IMAGES_DIR);
        } catch (IOException e) {
            ImageViewer.LOGGER.error("Failed to create imageviewer/images directory", e);
        }
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
