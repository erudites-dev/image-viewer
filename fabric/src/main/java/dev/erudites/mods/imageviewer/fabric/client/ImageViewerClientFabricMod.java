package dev.erudites.mods.imageviewer.fabric.client;

import dev.erudites.mods.imageviewer.client.ImageViewerClient;
import dev.erudites.mods.imageviewer.network.OpenImagePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class ImageViewerClientFabricMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        KeyMappingHelper.registerKeyMapping(ImageViewerClient.OPEN_KEY);

        ClientPlayNetworking.registerGlobalReceiver(OpenImagePayload.TYPE, (payload, context) ->
            ImageViewerClient.openImagePayload(context.client(), payload.port(), payload.categories())
        );

        ClientTickEvents.END_CLIENT_TICK.register(ImageViewerClient::tick);
    }
}
