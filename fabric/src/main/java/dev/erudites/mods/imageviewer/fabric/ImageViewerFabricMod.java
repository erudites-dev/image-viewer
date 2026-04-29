package dev.erudites.mods.imageviewer.fabric;

import dev.erudites.mods.imageviewer.ImageViewer;
import dev.erudites.mods.imageviewer.command.ImageViewerCommands;
import dev.erudites.mods.imageviewer.network.OpenImagePayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public class ImageViewerFabricMod implements ModInitializer {

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.clientboundPlay().register(OpenImagePayload.TYPE, OpenImagePayload.CODEC);

        ServerLifecycleEvents.SERVER_STARTED.register(_ -> ImageViewer.startServer());
        ServerLifecycleEvents.SERVER_STOPPING.register(_ -> ImageViewer.stopServer());

        ServerPlayConnectionEvents.JOIN.register((handler, _, _) -> {
            OpenImagePayload payload = ImageViewer.buildPayload();
            if (payload == null) {
                return;
            }
            ServerPlayNetworking.send(handler.player, payload);
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, _, _) ->
            dispatcher.register(ImageViewerCommands.reloadCommand(ServerPlayNetworking::send))
        );
    }
}
