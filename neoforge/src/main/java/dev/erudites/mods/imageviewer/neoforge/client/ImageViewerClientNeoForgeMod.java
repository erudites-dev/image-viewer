package dev.erudites.mods.imageviewer.neoforge.client;

import dev.erudites.mods.imageviewer.ImageViewer;
import dev.erudites.mods.imageviewer.client.ImageViewerClient;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = ImageViewer.MODID, dist = Dist.CLIENT)
public class ImageViewerClientNeoForgeMod {

    public ImageViewerClientNeoForgeMod(IEventBus modBus) {
        modBus.addListener(this::registerKeyMappings);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post _) ->
            ImageViewerClient.tick(Minecraft.getInstance())
        );
    }

    private void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(ImageViewerClient.OPEN_KEY);
    }
}
