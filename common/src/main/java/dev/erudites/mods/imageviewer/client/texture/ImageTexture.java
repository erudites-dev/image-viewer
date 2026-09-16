package dev.erudites.mods.imageviewer.client.texture;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class ImageTexture implements AutoCloseable {

    private static final AtomicInteger LIVE_COUNT = new AtomicInteger();

    private record GlTile(int x, int y, int width, int height, int textureId) {}

    private final int width;
    private final int height;
    private final long byteSize;
    private final List<GlTile> tiles;
    private boolean closed;

    private ImageTexture(final int width, final int height, final long byteSize, final List<GlTile> tiles) {
        this.width = width;
        this.height = height;
        this.byteSize = byteSize;
        this.tiles = List.copyOf(tiles);
        LIVE_COUNT.incrementAndGet();
    }

    public static ImageTexture upload(final DecodedImage image) {
        RenderSystem.assertOnRenderThread();
        List<GlTile> tiles = new ArrayList<>(image.tiles().size());
        try {
            for (DecodedImage.Tile tile : image.tiles()) {
                tiles.add(uploadTile(tile));
            }
        } catch (RuntimeException e) {
            for (GlTile tile : tiles) {
                TextureUtil.releaseTextureId(tile.textureId());
            }
            throw e;
        }
        return new ImageTexture(image.width(), image.height(), image.byteSize(), tiles);
    }

    private static GlTile uploadTile(final DecodedImage.Tile tile) {
        PixelBuffer[] levels = tile.mipLevels();
        int textureId = TextureUtil.generateTextureId();
        try {
            TextureUtil.prepareImage(NativeImage.InternalGlFormat.RGBA, textureId, levels.length - 1, tile.width(), tile.height());
            GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
            for (int level = 0; level < levels.length; level++) {
                PixelBuffer source = levels[level];
                GlStateManager._texSubImage2D(
                    GL11.GL_TEXTURE_2D,
                    level,
                    0,
                    0,
                    source.width(),
                    source.height(),
                    GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE,
                    source.address()
                );
            }
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        } catch (RuntimeException e) {
            TextureUtil.releaseTextureId(textureId);
            throw e;
        }
        return new GlTile(tile.x(), tile.y(), tile.width(), tile.height(), textureId);
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
        Matrix4f pose = graphics.pose().last().pose();
        double scaleX = (double) (x1 - x0) / this.width;
        double scaleY = (double) (y1 - y0) / this.height;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        for (GlTile tile : this.tiles) {
            float left = x0 + Math.round(tile.x() * scaleX);
            float right = x0 + Math.round((tile.x() + tile.width()) * scaleX);
            float top = y0 + Math.round(tile.y() * scaleY);
            float bottom = y0 + Math.round((tile.y() + tile.height()) * scaleY);
            if (right <= left || bottom <= top) {
                continue;
            }
            RenderSystem.setShaderTexture(0, tile.textureId());
            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            builder.addVertex(pose, left, top, 0.0F).setUv(0.0F, 0.0F);
            builder.addVertex(pose, left, bottom, 0.0F).setUv(0.0F, 1.0F);
            builder.addVertex(pose, right, bottom, 0.0F).setUv(1.0F, 1.0F);
            builder.addVertex(pose, right, top, 0.0F).setUv(1.0F, 0.0F);
            BufferUploader.drawWithShader(builder.buildOrThrow());
        }
        RenderSystem.disableBlend();
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
        for (GlTile tile : this.tiles) {
            TextureUtil.releaseTextureId(tile.textureId());
        }
        LIVE_COUNT.decrementAndGet();
    }
}
