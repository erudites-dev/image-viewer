package dev.erudites.mods.imageviewer.mixin.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GuiGraphics.class)
public interface GuiGraphicsAccessor {

    @Invoker("submitBlit")
    void imageviewer$submitBlit(RenderPipeline pipeline, GpuTextureView texture, int x0, int y0, int x1, int y1, float u0, float u1, float v0, float v1, int color);
}
