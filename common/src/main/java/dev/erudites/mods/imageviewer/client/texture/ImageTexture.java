package dev.erudites.mods.imageviewer.client.texture;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

public final class ImageTexture implements AutoCloseable {

    private static final AtomicInteger LIVE_COUNT = new AtomicInteger();

    private record GpuTile(int x, int y, int width, int height, GpuTexture texture) {
        void close() {
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
                GpuTexture texture = device.createTexture(label, TextureFormat.RGBA8, tile.width(), tile.height(), levels.length);
                try {
                    for (int level = 0; level < levels.length; level++) {
                        NativeImage source = levels[level];
                        encoder.writeToTexture(texture, source, level, 0, 0, source.getWidth(), source.getHeight(), 0, 0);
                    }
                    texture.setAddressMode(AddressMode.CLAMP_TO_EDGE);
                    texture.setTextureFilter(FilterMode.LINEAR, true);
                } catch (RuntimeException | Error e) {
                    texture.close();
                    throw e;
                }
                tiles.add(new GpuTile(tile.x(), tile.y(), tile.width(), tile.height(), texture));
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
        graphics.flush();
        Matrix4f pose = graphics.pose().last().pose();
        double scaleX = (double) (x1 - x0) / this.width;
        double scaleY = (double) (y1 - y0) / this.height;

        RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
        for (GpuTile tile : this.tiles) {
            float left = x0 + Math.round(tile.x() * scaleX);
            float right = x0 + Math.round((tile.x() + tile.width()) * scaleX);
            float top = y0 + Math.round(tile.y() * scaleY);
            float bottom = y0 + Math.round((tile.y() + tile.height()) * scaleY);
            if (right <= left || bottom <= top) {
                continue;
            }
            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            builder.addVertex(pose, left, top, 0.0F).setUv(0.0F, 0.0F).setColor(-1);
            builder.addVertex(pose, left, bottom, 0.0F).setUv(0.0F, 1.0F).setColor(-1);
            builder.addVertex(pose, right, bottom, 0.0F).setUv(1.0F, 1.0F).setColor(-1);
            builder.addVertex(pose, right, top, 0.0F).setUv(1.0F, 0.0F).setColor(-1);
            try (MeshData mesh = builder.buildOrThrow()) {
                GpuBuffer vertices = DefaultVertexFormat.POSITION_TEX_COLOR.uploadImmediateVertexBuffer(mesh.vertexBuffer());
                RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
                GpuBuffer indexBuffer = indices.getBuffer(mesh.drawState().indexCount());
                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    target.getColorTexture(), OptionalInt.empty(), target.getDepthTexture(), OptionalDouble.empty()
                )) {
                    pass.setPipeline(RenderPipelines.GUI_TEXTURED);
                    pass.setVertexBuffer(0, vertices);
                    pass.setIndexBuffer(indexBuffer, indices.type());
                    pass.bindSampler("Sampler0", tile.texture());
                    if (RenderSystem.SCISSOR_STATE.isEnabled()) {
                        pass.enableScissor(RenderSystem.SCISSOR_STATE);
                    }
                    pass.drawIndexed(0, mesh.drawState().indexCount());
                }
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
