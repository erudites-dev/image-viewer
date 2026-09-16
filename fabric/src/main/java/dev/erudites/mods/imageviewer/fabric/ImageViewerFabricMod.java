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

    private static final PayloadSender SENDER = ServerPlayNetworking::createClientboundPacket;

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.clientboundPlay().register(CatalogPayload.TYPE, CatalogPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ImageDataPayload.TYPE, ImageDataPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ImageErrorPayload.TYPE, ImageErrorPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ImageRequestPayload.TYPE, ImageRequestPayload.CODEC);

        ServerLifecycleEvents.SERVER_STARTED.register(_ -> ImageViewer.start());
        ServerLifecycleEvents.SERVER_STOPPING.register(_ -> ImageViewer.stop());

        ServerPlayConnectionEvents.JOIN.register((handler, _, _) ->
            ImageViewer.sendCatalog(handler.player, SENDER)
        );
        ServerPlayConnectionEvents.DISCONNECT.register((handler, _) ->
            ImageViewer.onPlayerLeave(handler.player.getUUID())
        );

        ServerPlayNetworking.registerGlobalReceiver(ImageRequestPayload.TYPE, (payload, context) ->
            ImageViewer.handleRequest(context.player(), payload.entries(), SENDER)
        );

        CommandRegistrationCallback.EVENT.register((dispatcher, _, _) ->
            dispatcher.register(ImageViewerCommands.reloadCommand(SENDER))
        );
    }
}
