package dev.erudites.mods.imageviewer.fabric;

import dev.erudites.mods.imageviewer.ImageViewer;
import dev.erudites.mods.imageviewer.command.ImageViewerCommands;
import dev.erudites.mods.imageviewer.network.PayloadSender;
import dev.erudites.mods.imageviewer.network.payload.CatalogPayload;
import dev.erudites.mods.imageviewer.network.payload.ImageDataPayload;
import dev.erudites.mods.imageviewer.network.payload.ImageErrorPayload;
import dev.erudites.mods.imageviewer.network.payload.ImageRequestPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public class ImageViewerFabricMod implements ModInitializer {

    private static final PayloadSender SENDER = ServerPlayNetworking::createS2CPacket;

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playS2C().register(CatalogPayload.TYPE, CatalogPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ImageDataPayload.TYPE, ImageDataPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ImageErrorPayload.TYPE, ImageErrorPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ImageRequestPayload.TYPE, ImageRequestPayload.CODEC);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> ImageViewer.start());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> ImageViewer.stop());

        ServerPlayConnectionEvents.JOIN.register((handler, packetSender, server) ->
            ImageViewer.sendCatalog(handler.player, SENDER)
        );
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            ImageViewer.onPlayerLeave(handler.player.getUUID())
        );

        ServerPlayNetworking.registerGlobalReceiver(ImageRequestPayload.TYPE, (payload, context) ->
            ImageViewer.handleRequest(context.player(), payload.entries(), SENDER)
        );

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(ImageViewerCommands.reloadCommand(SENDER))
        );
    }
}
