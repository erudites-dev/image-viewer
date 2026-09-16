package dev.erudites.mods.imageviewer.client.texture;

import com.mojang.blaze3d.platform.NativeImage;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

public final class ImageDecoder {

    public static final long MAX_PIXELS = 8192L * 8192L;

    private static final int CHANNELS = 4;
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    private static final float[] SRGB_TO_LINEAR = new float[256];
    private static final int LINEAR_TO_SRGB_STEPS = 4096;
    private static final byte[] LINEAR_TO_SRGB = new byte[LINEAR_TO_SRGB_STEPS + 1];

    static {
        for (int i = 0; i < SRGB_TO_LINEAR.length; i++) {
            double c = i / 255.0;
            SRGB_TO_LINEAR[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
        for (int i = 0; i <= LINEAR_TO_SRGB_STEPS; i++) {
            double l = (double) i / LINEAR_TO_SRGB_STEPS;
            double s = l <= 0.0031308 ? l * 12.92 : 1.055 * Math.pow(l, 1.0 / 2.4) - 0.055;
            LINEAR_TO_SRGB[i] = (byte) Math.round(Math.clamp(s, 0.0, 1.0) * 255.0);
        }
    }

    private ImageDecoder() {}

    public static boolean isSupportedFormat(final byte[] bytes) {
        return hasPrefix(bytes, PNG_SIGNATURE) || hasPrefix(bytes, JPEG_SIGNATURE);
    }

    public static DecodedImage decode(final byte[] bytes, final int maxTextureSize) throws IOException {
        if (!isSupportedFormat(bytes)) {
            throw new IOException("Unsupported image format");
        }
        if (maxTextureSize <= 0) {
            throw new IllegalArgumentException("Invalid max texture size " + maxTextureSize);
        }

        ByteBuffer encoded = MemoryUtil.memAlloc(bytes.length);
        try {
            encoded.put(bytes).flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer width = stack.mallocInt(1);
                IntBuffer height = stack.mallocInt(1);
                IntBuffer components = stack.mallocInt(1);

                if (!STBImage.stbi_info_from_memory(encoded, width, height, components)) {
                    throw new IOException("Could not read image header: " + STBImage.stbi_failure_reason());
                }
                long pixelCount = (long) width.get(0) * height.get(0);
                if (width.get(0) <= 0 || height.get(0) <= 0 || pixelCount > MAX_PIXELS) {
                    throw new IOException("Image dimensions " + width.get(0) + "x" + height.get(0) + " exceed the supported limit");
                }

                ByteBuffer pixels = STBImage.stbi_load_from_memory(encoded, width, height, components, CHANNELS);
                if (pixels == null) {
                    throw new IOException("Could not decode image: " + STBImage.stbi_failure_reason());
                }
                try {
                    return split(MemoryUtil.memAddress(pixels), width.get(0), height.get(0), maxTextureSize);
                } finally {
                    STBImage.stbi_image_free(pixels);
                }
            }
        } finally {
            MemoryUtil.memFree(encoded);
        }
    }

    private static DecodedImage split(final long pixels, final int width, final int height, final int tileSize) {
        List<DecodedImage.Tile> tiles = new ArrayList<>();
        try {
            for (int tileY = 0; tileY < height; tileY += tileSize) {
                for (int tileX = 0; tileX < width; tileX += tileSize) {
                    int tileWidth = Math.min(tileSize, width - tileX);
                    int tileHeight = Math.min(tileSize, height - tileY);
                    NativeImage[] levels = new NativeImage[mipLevelCount(tileWidth, tileHeight)];
                    try {
                        levels[0] = copyRegion(pixels, width, tileX, tileY, tileWidth, tileHeight);
                        for (int level = 1; level < levels.length; level++) {
                            levels[level] = downsample(levels[level - 1]);
                        }
                    } catch (RuntimeException | Error e) {
                        DecodedImage.closeAll(levels);
                        throw e;
                    }
                    tiles.add(new DecodedImage.Tile(tileX, tileY, levels));
                }
            }
            return new DecodedImage(width, height, tiles);
        } catch (RuntimeException | Error e) {
            for (DecodedImage.Tile tile : tiles) {
                DecodedImage.closeAll(tile.mipLevels());
            }
            throw e;
        }
    }

    public static int mipLevelCount(final int width, final int height) {
        return 32 - Integer.numberOfLeadingZeros(Math.min(width, height));
    }

    private static NativeImage copyRegion(final long pixels, final int sourceWidth, final int x, final int y, final int width, final int height) {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        long rowBytes = (long) width * CHANNELS;
        for (int row = 0; row < height; row++) {
            long source = pixels + ((long) (y + row) * sourceWidth + x) * CHANNELS;
            long destination = image.getPointer() + row * rowBytes;
            MemoryUtil.memCopy(source, destination, rowBytes);
        }
        return image;
    }

    static NativeImage downsample(final NativeImage source) {
        int width = source.getWidth() >> 1;
        int height = source.getHeight() >> 1;
        int sourceWidth = source.getWidth();
        long src = source.getPointer();
        NativeImage target = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        long dst = target.getPointer();

        for (int y = 0; y < height; y++) {
            long rowA = src + (long) (y * 2) * sourceWidth * CHANNELS;
            long rowB = rowA + (long) sourceWidth * CHANNELS;
            long out = dst + (long) y * width * CHANNELS;
            for (int x = 0; x < width; x++) {
                long p00 = rowA + (long) x * 2 * CHANNELS;
                long p10 = p00 + CHANNELS;
                long p01 = rowB + (long) x * 2 * CHANNELS;
                long p11 = p01 + CHANNELS;

                int a00 = MemoryUtil.memGetByte(p00 + 3) & 0xFF;
                int a10 = MemoryUtil.memGetByte(p10 + 3) & 0xFF;
                int a01 = MemoryUtil.memGetByte(p01 + 3) & 0xFF;
                int a11 = MemoryUtil.memGetByte(p11 + 3) & 0xFF;
                int alphaSum = a00 + a10 + a01 + a11;

                long o = out + (long) x * CHANNELS;
                if (alphaSum == 0) {
                    MemoryUtil.memPutInt(o, 0);
                    continue;
                }
                for (int c = 0; c < 3; c++) {
                    float linear = SRGB_TO_LINEAR[MemoryUtil.memGetByte(p00 + c) & 0xFF] * a00
                        + SRGB_TO_LINEAR[MemoryUtil.memGetByte(p10 + c) & 0xFF] * a10
                        + SRGB_TO_LINEAR[MemoryUtil.memGetByte(p01 + c) & 0xFF] * a01
                        + SRGB_TO_LINEAR[MemoryUtil.memGetByte(p11 + c) & 0xFF] * a11;
                    MemoryUtil.memPutByte(o + c, toSrgb(linear / alphaSum));
                }
                MemoryUtil.memPutByte(o + 3, (byte) ((alphaSum + 2) / 4));
            }
        }
        return target;
    }

    private static byte toSrgb(final float linear) {
        int index = Math.round(Math.clamp(linear, 0.0F, 1.0F) * LINEAR_TO_SRGB_STEPS);
        return LINEAR_TO_SRGB[index];
    }

    private static boolean hasPrefix(final byte[] bytes, final byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
