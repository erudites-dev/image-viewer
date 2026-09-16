package dev.erudites.mods.imageviewer.server;

import dev.erudites.mods.imageviewer.ImageViewer;
import dev.erudites.mods.imageviewer.network.ImageHashes;
import dev.erudites.mods.imageviewer.network.payload.CatalogPayload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class ImageCatalog {

    public record Image(String hash, int size, Path path) {}

    public record Category(String name, List<Image> images) {}

    private record HashCacheKey(Path path, long size, long modifiedMillis) {}

    private static final Comparator<Path> IMAGE_ORDER = Comparator
        .comparing((Path path) -> extractDigits(path.getFileName().toString()), ImageCatalog::compareNumeric)
        .thenComparing(path -> path.getFileName().toString());

    private final List<Category> categories;
    private final Map<String, Image> byHash;
    private final Map<HashCacheKey, String> hashCache;

    private ImageCatalog(final List<Category> categories, final Map<HashCacheKey, String> hashCache) {
        this.categories = List.copyOf(categories);
        this.byHash = new HashMap<>();
        for (Category category : this.categories) {
            for (Image image : category.images()) {
                this.byHash.putIfAbsent(image.hash(), image);
            }
        }
        this.hashCache = hashCache;
    }

    public static ImageCatalog empty() {
        return new ImageCatalog(List.of(), Map.of());
    }

    public static ImageCatalog scan(final Path imagesDir, final int maxImageBytes, final ImageCatalog previous) {
        Map<HashCacheKey, String> hashCache = new HashMap<>();
        List<Category> categories = new ArrayList<>();
        try {
            Files.createDirectories(imagesDir);

            List<Image> mainImages = scanDirectory(imagesDir, maxImageBytes, previous.hashCache, hashCache);
            if (!mainImages.isEmpty()) {
                categories.add(new Category(CatalogPayload.MAIN_CATEGORY, mainImages));
            }

            List<Path> subdirs;
            try (Stream<Path> stream = Files.list(imagesDir)) {
                subdirs = stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            }
            for (Path subdir : subdirs) {
                String name = subdir.getFileName().toString();
                if (CatalogPayload.MAIN_CATEGORY.equals(name)) {
                    ImageViewer.LOGGER.warn("Skipping image category '{}': the name is reserved", name);
                    continue;
                }
                List<Image> images = scanDirectory(subdir, maxImageBytes, previous.hashCache, hashCache);
                if (!images.isEmpty()) {
                    categories.add(new Category(name, images));
                }
            }
        } catch (IOException e) {
            ImageViewer.LOGGER.error("Failed to scan image directory {}", imagesDir, e);
        }
        return new ImageCatalog(categories, hashCache);
    }

    private static List<Image> scanDirectory(
        final Path dir,
        final int maxImageBytes,
        final Map<HashCacheKey, String> previousHashes,
        final Map<HashCacheKey, String> hashes
    ) throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(Files::isRegularFile)
                .filter(path -> isSupportedImage(path.getFileName().toString()))
                .sorted(IMAGE_ORDER)
                .toList();
        }

        List<Image> images = new ArrayList<>(files.size());
        for (Path file : files) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
                long size = attributes.size();
                if (size <= 0 || size > maxImageBytes) {
                    ImageViewer.LOGGER.warn("Skipping image {}: size {} bytes exceeds maxImageBytes {}", file, size, maxImageBytes);
                    continue;
                }
                HashCacheKey key = new HashCacheKey(file.toAbsolutePath().normalize(), size, attributes.lastModifiedTime().toMillis());
                String hash = previousHashes.get(key);
                if (hash == null) {
                    hash = ImageHashes.sha256(file);
                }
                hashes.put(key, hash);
                images.add(new Image(hash, (int) size, file));
            } catch (IOException e) {
                ImageViewer.LOGGER.error("Failed to read image {}", file, e);
            }
        }
        return images;
    }

    public List<Category> categories() {
        return this.categories;
    }

    public Optional<Image> find(final String hash) {
        return Optional.ofNullable(this.byHash.get(hash));
    }

    public boolean contains(final String hash) {
        return this.byHash.containsKey(hash);
    }

    public CatalogPayload toPayload(final boolean keepAspectRatio) {
        List<CatalogPayload.Category> payloadCategories = this.categories.stream()
            .map(category -> new CatalogPayload.Category(
                category.name(),
                category.images().stream()
                    .map(image -> new CatalogPayload.Entry(image.hash(), image.size()))
                    .toList()
            ))
            .toList();
        return new CatalogPayload(keepAspectRatio, payloadCategories);
    }

    public static boolean isSupportedImage(final String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }

    private static String extractDigits(final String name) {
        return name.replaceAll("[^0-9]", "").replaceFirst("^0+(?=.)", "");
    }

    private static int compareNumeric(final String a, final String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return Boolean.compare(!a.isEmpty(), !b.isEmpty());
        }
        int byLength = Integer.compare(a.length(), b.length());
        return byLength != 0 ? byLength : a.compareTo(b);
    }
}
