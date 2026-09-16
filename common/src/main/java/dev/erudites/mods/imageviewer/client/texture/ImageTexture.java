package dev.erudites.mods.imageviewer.client.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import dev.erudites.mods.imageviewer.mixin.client.GuiGraphicsAccessor;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class ImageTexture implements AutoCloseable {

    private static final AtomicInteger LIVE_COUNT = new AtomicInteger();

    private record GpuTile(int x, int y, int width, int height, GpuTexture texture, GpuTextureView view) {
        void close() {
            this.view.close();
            this.texture.close();
        }
    }

    private final int width;
    private final int height;
    private final long byteSize;
    private final List<GpuTile> tiles;
    private boolean closed;

    private ImageTexture(final int width, final int height, final long byteSize, final List<GpuTile> tiles) {
        this.width = width;
        this.height = height;
        this.byteSize = byteSize;
        this.tiles = List.copyOf(tiles);
        LIVE_COUNT.incrementAndGet();
    }

    public static ImageTexture upload(final String label, final DecodedImage image) {
        RenderSystem.assertOnRenderThread();
        GpuDevice device = RenderSystem.getDevice();
        CommandEncoder encoder = device.createCommandEncoder();
        List<GpuTile> tiles = new ArrayList<>(image.tiles().size());
        try {
            for (DecodedImage.Tile tile : image.tiles()) {
                NativeImage[] levels = tile.mipLevels();
                GpuTexture texture = device.createTexture(
                    label,
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    TextureFormat.RGBA8,
                    tile.width(),
                    tile.height(),
                    1,
                    levels.length
                );
                GpuTextureView view;
                try {
                    for (int level = 0; level < levels.length; level++) {
                        NativeImage source = levels[level];
                        encoder.writeToTexture(texture, source, level, 0, 0, 0, source.getWidth(), source.getHeight(), 0, 0);
                    }
                    texture.setAddressMode(AddressMode.CLAMP_TO_EDGE);
                    texture.setTextureFilter(FilterMode.LINEAR, true);
                    view = device.createTextureView(texture);
                } catch (RuntimeException | Error e) {
                    texture.close();
                    throw e;
                }
                tiles.add(new GpuTile(tile.x(), tile.y(), tile.width(), tile.height(), texture, view));
            }
        } catch (RuntimeException | Error e) {
            for (GpuTile tile : tiles) {
                tile.close();
            }
            throw e;
        }
        return new ImageTexture(image.width(), image.height(), image.byteSize(), tiles);
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public long byteSize() {
        return this.byteSize;
    }

    public void draw(final GuiGraphics graphics, final int x0, final int y0, final int x1, final int y1) {
        if (this.closed) {
            return;
        }
        double scaleX = (double) (x1 - x0) / this.width;
        double scaleY = (double) (y1 - y0) / this.height;
        for (GpuTile tile : this.tiles) {
            int left = x0 + (int) Math.round(tile.x() * scaleX);
            int right = x0 + (int) Math.round((tile.x() + tile.width()) * scaleX);
            int top = y0 + (int) Math.round(tile.y() * scaleY);
            int bottom = y0 + (int) Math.round((tile.y() + tile.height()) * scaleY);
            if (right > left && bottom > top) {
                ((GuiGraphicsAccessor) graphics).imageviewer$submitBlit(
                    RenderPipelines.GUI_TEXTURED,
                    tile.view(),
                    left,
                    top,
                    right,
                    bottom,
                    0.0F,
                    1.0F,
                    0.0F,
                    1.0F,
                    0xFFFFFFFF
                );
            }
        }
    }

    public static int liveCount() {
        return LIVE_COUNT.get();
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        for (GpuTile tile : this.tiles) {
            tile.close();
        }
        LIVE_COUNT.decrementAndGet();
    }
}
