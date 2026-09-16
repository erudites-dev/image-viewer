package dev.erudites.mods.imageviewer.client.texture;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageDecoderTest {

    @Test
    void decodesPngWithoutLosingPixels() throws IOException {
        BufferedImage source = this.randomImage(37, 23, BufferedImage.TYPE_INT_ARGB);

        try (DecodedImage decoded = ImageDecoder.decode(this.encode(source, "png"), 1024)) {
            assertEquals(37, decoded.width());
            assertEquals(23, decoded.height());
            assertEquals(1, decoded.tiles().size());
            this.assertPixelsEqual(source, decoded);
        }
    }

    @Test
    void splitsImagesLargerThanMaxTextureSizeIntoExactTiles() throws IOException {
        BufferedImage source = this.randomImage(70, 45, BufferedImage.TYPE_INT_ARGB);

        try (DecodedImage decoded = ImageDecoder.decode(this.encode(source, "png"), 32)) {
            assertEquals(6, decoded.tiles().size());
            for (DecodedImage.Tile tile : decoded.tiles()) {
                assertTrue(tile.width() <= 32);
                assertTrue(tile.height() <= 32);
            }
            this.assertPixelsEqual(source, decoded);
        }
    }

    @Test
    void decodesJpegAtOriginalResolution() throws IOException {
        BufferedImage source = this.randomImage(64, 48, BufferedImage.TYPE_INT_RGB);

        try (DecodedImage decoded = ImageDecoder.decode(this.encode(source, "jpg"), 1024)) {
            assertEquals(64, decoded.width());
            assertEquals(48, decoded.height());
        }
    }

    @Test
    void buildsMipChainThatFitsGpuLevelSizes() throws IOException {
        BufferedImage source = this.randomImage(100, 7, BufferedImage.TYPE_INT_ARGB);

        try (DecodedImage decoded = ImageDecoder.decode(this.encode(source, "png"), 1024)) {
            PixelBuffer[] levels = decoded.tiles().getFirst().mipLevels();
            assertEquals(3, levels.length);
            for (int level = 0; level < levels.length; level++) {
                assertEquals(100 >> level, levels[level].width());
                assertEquals(7 >> level, levels[level].height());
            }
        }
    }

    @Test
    void averagesMipLevelsWithAlphaWeighting() throws IOException {
        BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, 0xFFFFFFFF);
        source.setRGB(1, 0, 0x00FF0000);
        source.setRGB(0, 1, 0xFFFFFFFF);
        source.setRGB(1, 1, 0x00FF0000);

        try (DecodedImage decoded = ImageDecoder.decode(this.encode(source, "png"), 1024)) {
            PixelBuffer mip = decoded.tiles().getFirst().mipLevels()[1];
            assertEquals(0x80FFFFFF, mip.pixelArgb(0, 0));
        }
    }

    @Test
    void closeReleasesEveryPixelBuffer() throws IOException {
        DecodedImage decoded = ImageDecoder.decode(this.encode(this.randomImage(70, 45, BufferedImage.TYPE_INT_ARGB), "png"), 32);
        List<DecodedImage.Tile> tiles = decoded.tiles();

        decoded.close();
        decoded.close();

        for (DecodedImage.Tile tile : tiles) {
            for (PixelBuffer level : tile.mipLevels()) {
                assertTrue(level.isClosed());
            }
        }
    }

    @Test
    void rejectsUnsupportedFormats() throws IOException {
        byte[] gif = this.encode(this.randomImage(4, 4, BufferedImage.TYPE_INT_RGB), "gif");

        assertFalse(ImageDecoder.isSupportedFormat(gif));
        assertThrows(IOException.class, () -> ImageDecoder.decode(gif, 1024));
    }

    @Test
    void rejectsTruncatedPng() throws IOException {
        byte[] png = this.encode(this.randomImage(16, 16, BufferedImage.TYPE_INT_ARGB), "png");
        byte[] truncated = Arrays.copyOf(png, 20);

        assertThrows(IOException.class, () -> ImageDecoder.decode(truncated, 1024));
    }

    @Test
    void rejectsImagesAbovePixelLimit() {
        byte[] header = this.pngHeader(8193, 8192);

        assertThrows(IOException.class, () -> ImageDecoder.decode(header, 1024));
    }

    private void assertPixelsEqual(final BufferedImage source, final DecodedImage decoded) {
        for (DecodedImage.Tile tile : decoded.tiles()) {
            PixelBuffer base = tile.mipLevels()[0];
            for (int y = 0; y < base.height(); y++) {
                for (int x = 0; x < base.width(); x++) {
                    int expected = source.getRGB(tile.x() + x, tile.y() + y);
                    assertEquals(expected, base.pixelArgb(x, y), "pixel " + (tile.x() + x) + "," + (tile.y() + y));
                }
            }
        }
    }

    private BufferedImage randomImage(final int width, final int height, final int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        Random random = new Random((long) width * 31 + height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt());
            }
        }
        return image;
    }

    private byte[] encode(final BufferedImage image, final String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, out)) {
            throw new IOException("No writer for " + format);
        }
        return out.toByteArray();
    }

    private byte[] pngHeader(final int width, final int height) {
        ByteBuffer buffer = ByteBuffer.allocate(33);
        buffer.put(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        buffer.putInt(13);
        buffer.put(new byte[] {'I', 'H', 'D', 'R'});
        buffer.putInt(width);
        buffer.putInt(height);
        buffer.put(new byte[] {8, 6, 0, 0, 0});
        CRC32 crc = new CRC32();
        crc.update(buffer.array(), 12, 17);
        buffer.putInt((int) crc.getValue());
        return buffer.array();
    }
}
