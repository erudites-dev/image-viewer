package dev.erudites.mods.imageviewer.fabric.client;

import dev.erudites.mods.imageviewer.client.ImageViewerClient;
import dev.erudites.mods.imageviewer.network.payload.CatalogPayload;
import dev.erudites.mods.imageviewer.network.payload.ImageDataPayload;
import dev.erudites.mods.imageviewer.network.payload.ImageErrorPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class ImageViewerClientFabricMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        KeyMappingHelper.registerKeyMapping(ImageViewerClient.OPEN_KEY);
        ImageViewerClient.init(ClientPlayNetworking::send);

        ClientPlayNetworking.registerGlobalReceiver(CatalogPayload.TYPE, (payload, context) ->
            ImageViewerClient.onCatalog(context.client(), payload)
        );
        ClientPlayNetworking.registerGlobalReceiver(ImageDataPayload.TYPE, (payload, context) ->
            ImageViewerClient.onData(context.client(), payload)
        );
        ClientPlayNetworking.registerGlobalReceiver(ImageErrorPayload.TYPE, (payload, context) ->
            ImageViewerClient.onError(context.client(), payload)
        );

        ClientPlayConnectionEvents.DISCONNECT.register((_, client) -> ImageViewerClient.onDisconnect(client));
        ClientTickEvents.END_CLIENT_TICK.register(ImageViewerClient::tick);
    }
}
