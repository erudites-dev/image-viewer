package dev.erudites.mods.imageviewer.client.cache;

import dev.erudites.mods.imageviewer.network.ImageHashes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiskImageCacheTest {

    @TempDir
    Path directory;

    @Test
    void readsBackWrittenImage() {
        DiskImageCache cache = new DiskImageCache(this.directory, 1024);
        byte[] data = this.bytes("image");
        String hash = ImageHashes.sha256(data);

        cache.write(hash, data);

        assertArrayEquals(data, cache.read(hash, data.length));
        assertFalse(Files.exists(this.directory.resolve(hash + ".tmp")));
    }

    @Test
    void returnsEmptyForMissingImage() {
        DiskImageCache cache = new DiskImageCache(this.directory.resolve("missing"), 1024);

        assertEquals(0, cache.read(ImageHashes.sha256(this.bytes("x")), 1).length);
    }

    @Test
    void deletesCorruptedImage() throws IOException {
        DiskImageCache cache = new DiskImageCache(this.directory, 1024);
        byte[] data = this.bytes("image");
        String hash = ImageHashes.sha256(data);
        Files.write(this.directory.resolve(hash), this.bytes("imagf"));

        assertEquals(0, cache.read(hash, data.length).length);
        assertFalse(Files.exists(this.directory.resolve(hash)));
    }

    @Test
    void deletesImageWithUnexpectedSize() throws IOException {
        DiskImageCache cache = new DiskImageCache(this.directory, 1024);
        byte[] data = this.bytes("image");
        String hash = ImageHashes.sha256(data);
        Files.write(this.directory.resolve(hash), data);

        assertEquals(0, cache.read(hash, data.length + 1).length);
        assertFalse(Files.exists(this.directory.resolve(hash)));
    }

    @Test
    void refusesInvalidHashes() {
        DiskImageCache cache = new DiskImageCache(this.directory, 1024);

        cache.write("../escape", this.bytes("image"));

        assertEquals(0, cache.read("../escape", 5).length);
        assertFalse(Files.exists(this.directory.getParent().resolve("escape")));
    }

    @Test
    void prunesLeastRecentlyUsedImagesBeyondLimit() throws IOException {
        DiskImageCache cache = new DiskImageCache(this.directory, 10);
        byte[] oldest = this.bytes("aaaa");
        byte[] middle = this.bytes("bbbb");
        byte[] newest = this.bytes("cccc");
        String oldestHash = ImageHashes.sha256(oldest);
        String middleHash = ImageHashes.sha256(middle);
        String newestHash = ImageHashes.sha256(newest);

        cache.write(oldestHash, oldest);
        Files.setLastModifiedTime(this.directory.resolve(oldestHash), FileTime.fromMillis(1_000));
        cache.write(middleHash, middle);
        Files.setLastModifiedTime(this.directory.resolve(middleHash), FileTime.fromMillis(2_000));
        cache.write(newestHash, newest);

        assertFalse(Files.exists(this.directory.resolve(oldestHash)));
        assertTrue(Files.exists(this.directory.resolve(middleHash)));
        assertTrue(Files.exists(this.directory.resolve(newestHash)));
    }

    @Test
    void pruneRemovesStrayFiles() throws IOException {
        DiskImageCache cache = new DiskImageCache(this.directory, 1024);
        Path stray = this.directory.resolve("0".repeat(ImageHashes.HEX_LENGTH) + ".tmp");
        Files.write(stray, this.bytes("partial"));

        cache.prune();

        assertFalse(Files.exists(stray));
    }

    private byte[] bytes(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
