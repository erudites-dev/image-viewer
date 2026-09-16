package dev.erudites.mods.imageviewer.server;

import dev.erudites.mods.imageviewer.network.ImageHashes;
import dev.erudites.mods.imageviewer.network.payload.CatalogPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageCatalogTest {

    private static final int MAX_BYTES = 1024;

    @TempDir
    Path directory;

    @Test
    void ordersImagesByNumberInFileName() throws IOException {
        this.write("10.png", "ten");
        this.write("2.png", "two");
        this.write("cover.png", "cover");
        this.write("1.jpg", "one");
        this.write("99999999999999999999.jpeg", "huge number");

        ImageCatalog catalog = ImageCatalog.scan(this.directory, MAX_BYTES, ImageCatalog.empty());

        assertEquals(List.of("cover.png", "1.jpg", "2.png", "10.png", "99999999999999999999.jpeg"), this.fileNames(catalog, 0));
    }

    @Test
    void buildsMainAndSubdirectoryCategories() throws IOException {
        this.write("1.png", "main");
        this.write("rules/1.png", "rule");
        this.write("empty/readme.txt", "not an image");
        this.write("alpha/1.PNG", "alpha");

        ImageCatalog catalog = ImageCatalog.scan(this.directory, MAX_BYTES, ImageCatalog.empty());

        assertEquals(
            List.of(CatalogPayload.MAIN_CATEGORY, "alpha", "rules"),
            catalog.categories().stream().map(ImageCatalog.Category::name).toList()
        );
    }

    @Test
    void skipsUnsupportedOversizedAndEmptyFiles() throws IOException {
        this.write("1.png", "ok");
        this.write("2.gif", "gif");
        this.write("3.png", "x".repeat(MAX_BYTES + 1));
        this.write("4.png", "");

        ImageCatalog catalog = ImageCatalog.scan(this.directory, MAX_BYTES, ImageCatalog.empty());

        assertEquals(List.of("1.png"), this.fileNames(catalog, 0));
    }

    @Test
    void ignoresSubdirectoryNamedLikeMainCategory() throws IOException {
        this.write("main/1.png", "shadow");

        ImageCatalog catalog = ImageCatalog.scan(this.directory, MAX_BYTES, ImageCatalog.empty());

        assertTrue(catalog.categories().isEmpty());
    }

    @Test
    void indexesImagesByContentHash() throws IOException {
        this.write("1.png", "same");
        this.write("other/1.png", "same");

        ImageCatalog catalog = ImageCatalog.scan(this.directory, MAX_BYTES, ImageCatalog.empty());
        String hash = ImageHashes.sha256("same".getBytes());

        assertTrue(catalog.contains(hash));
        assertEquals(4, catalog.find(hash).orElseThrow().size());
        assertFalse(catalog.contains("0".repeat(ImageHashes.HEX_LENGTH)));
    }

    @Test
    void reusesHashesOfUnchangedFiles() throws IOException {
        Path file = this.write("1.png", "aaaa");
        FileTime modified = Files.getLastModifiedTime(file);
        ImageCatalog first = ImageCatalog.scan(this.directory, MAX_BYTES, ImageCatalog.empty());

        Files.writeString(file, "bbbb");
        Files.setLastModifiedTime(file, modified);
        ImageCatalog second = ImageCatalog.scan(this.directory, MAX_BYTES, first);

        assertEquals(this.hashes(first), this.hashes(second));
    }

    @Test
    void rehashesModifiedFiles() throws IOException {
        Path file = this.write("1.png", "aaaa");
        ImageCatalog first = ImageCatalog.scan(this.directory, MAX_BYTES, ImageCatalog.empty());

        Files.writeString(file, "bbbbb");
        ImageCatalog second = ImageCatalog.scan(this.directory, MAX_BYTES, first);

        assertEquals(List.of(ImageHashes.sha256("bbbbb".getBytes())), this.hashes(second));
    }

    @Test
    void convertsToPayload() throws IOException {
        this.write("1.png", "main");
        this.write("rules/1.png", "rule");

        CatalogPayload payload = ImageCatalog.scan(this.directory, MAX_BYTES, ImageCatalog.empty()).toPayload(true);

        assertTrue(payload.keepAspectRatio());
        assertEquals(2, payload.categories().size());
        assertEquals(new CatalogPayload.Entry(ImageHashes.sha256("rule".getBytes()), 4), payload.categories().get(1).images().getFirst());
    }

    private Path write(final String relativePath, final String content) throws IOException {
        Path file = this.directory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private List<String> fileNames(final ImageCatalog catalog, final int categoryIndex) {
        return catalog.categories().get(categoryIndex).images().stream()
            .map(image -> image.path().getFileName().toString())
            .toList();
    }

    private List<String> hashes(final ImageCatalog catalog) {
        return catalog.categories().getFirst().images().stream().map(ImageCatalog.Image::hash).toList();
    }
}
