package dev.erudites.mods.imageviewer.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageHashesTest {

    private static final String ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    void hashesBytes() {
        assertEquals(ABC_SHA256, ImageHashes.sha256("abc".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void hashesFilesLikeBytes(@TempDir final Path directory) throws IOException {
        Path file = directory.resolve("abc");
        Files.writeString(file, "abc", StandardCharsets.US_ASCII);

        assertEquals(ABC_SHA256, ImageHashes.sha256(file));
    }

    @Test
    void acceptsLowercaseHex() {
        assertTrue(ImageHashes.isValid(ABC_SHA256));
    }

    @Test
    void rejectsMalformedHashes() {
        assertFalse(ImageHashes.isValid(null));
        assertFalse(ImageHashes.isValid(""));
        assertFalse(ImageHashes.isValid(ABC_SHA256.substring(1)));
        assertFalse(ImageHashes.isValid(ABC_SHA256 + "0"));
        assertFalse(ImageHashes.isValid(ABC_SHA256.toUpperCase()));
        assertFalse(ImageHashes.isValid("../" + ABC_SHA256.substring(3)));
    }
}
