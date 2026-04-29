package dev.erudites.mods.imageviewer.neoforge;

import dev.erudites.mods.imageviewer.ImageViewer;
import dev.erudites.mods.imageviewer.client.ImageViewerClient;
import dev.erudites.mods.imageviewer.command.ImageViewerCommands;
import dev.erudites.mods.imageviewer.network.OpenImagePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@Mod(ImageViewer.MODID)
public class ImageViewerNeoForgeMod {

    public ImageViewerNeoForgeMod(IEventBus modBus) {
        modBus.addListener(this::registerPayloads);

        IEventBus gameBus = NeoForge.EVENT_BUS;
        gameBus.addListener((ServerStartedEvent _) -> ImageViewer.startServer());
        gameBus.addListener((ServerStoppingEvent _) -> ImageViewer.stopServer());
        gameBus.addListener(this::playerJoin);
        gameBus.addListener(this::registerCommands);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(ImageViewer.MODID).optional();
        registrar.playToClient(
            OpenImagePayload.TYPE,
            OpenImagePayload.CODEC,
            (payload, context) -> context.enqueueWork(() ->
                ImageViewerClient.openImagePayload(
                    Minecraft.getInstance(),
                    payload.port(),
                    payload.categories()
                )
            )
        );
    }

    private void playerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        OpenImagePayload payload = ImageViewer.buildPayload();
        if (payload == null) {
            return;
        }
        PacketDistributor.sendToPlayer(player, payload);
    }

    private void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(ImageViewerCommands.reloadCommand(PacketDistributor::sendToPlayer));
    }
}
