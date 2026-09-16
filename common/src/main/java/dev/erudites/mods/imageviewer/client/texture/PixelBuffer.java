package dev.erudites.mods.imageviewer.client.texture;

import org.lwjgl.system.MemoryUtil;

public final class PixelBuffer implements AutoCloseable {

    public static final int CHANNELS = 4;

    private final int width;
    private final int height;
    private long address;

    private PixelBuffer(final int width, final int height, final long address) {
        this.width = width;
        this.height = height;
        this.address = address;
    }

    public static PixelBuffer allocate(final int width, final int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid pixel buffer size: " + width + "x" + height);
        }
        long bytes = (long) width * height * CHANNELS;
        long address = MemoryUtil.nmemAlloc(bytes);
        if (address == 0L) {
            throw new OutOfMemoryError("Unable to allocate pixel buffer of " + width + "x" + height);
        }
        return new PixelBuffer(width, height, address);
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public long byteSize() {
        return (long) this.width * this.height * CHANNELS;
    }

    public long address() {
        if (this.address == 0L) {
            throw new IllegalStateException("Pixel buffer is closed");
        }
        return this.address;
    }

    public boolean isClosed() {
        return this.address == 0L;
    }

    public int pixelArgb(final int x, final int y) {
        if (x < 0 || y < 0 || x >= this.width || y >= this.height) {
            throw new IndexOutOfBoundsException("Pixel " + x + "," + y + " outside " + this.width + "x" + this.height);
        }
        long offset = this.address() + ((long) y * this.width + x) * CHANNELS;
        int red = MemoryUtil.memGetByte(offset) & 0xFF;
        int green = MemoryUtil.memGetByte(offset + 1) & 0xFF;
        int blue = MemoryUtil.memGetByte(offset + 2) & 0xFF;
        int alpha = MemoryUtil.memGetByte(offset + 3) & 0xFF;
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    @Override
    public void close() {
        if (this.address != 0L) {
            MemoryUtil.nmemFree(this.address);
            this.address = 0L;
        }
    }
}
