package dev.erudites.mods.imageviewer.client.texture;

import com.mojang.blaze3d.platform.NativeImage;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class DecodedImage implements AutoCloseable {

    public record Tile(int x, int y, NativeImage[] mipLevels) {
        public int width() {
            return this.mipLevels[0].getWidth();
        }

        public int height() {
            return this.mipLevels[0].getHeight();
        }
    }

    private final int width;
    private final int height;
    private final List<Tile> tiles;
    private boolean closed;

    DecodedImage(final int width, final int height, final List<Tile> tiles) {
        this.width = width;
        this.height = height;
        this.tiles = List.copyOf(tiles);
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public List<Tile> tiles() {
        return this.tiles;
    }

    public long byteSize() {
        long total = 0;
        for (Tile tile : this.tiles) {
            for (NativeImage level : tile.mipLevels()) {
                total += (long) level.getWidth() * level.getHeight() * 4L;
            }
        }
        return total;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        for (Tile tile : this.tiles) {
            closeAll(tile.mipLevels());
        }
    }

    static void closeAll(final @Nullable NativeImage[] images) {
        for (NativeImage image : images) {
            if (image != null) {
                image.close();
            }
        }
    }
}
