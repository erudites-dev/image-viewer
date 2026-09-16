package dev.erudites.mods.imageviewer.neoforge;

import dev.erudites.mods.imageviewer.ImageViewer;
import dev.erudites.mods.imageviewer.client.ImageViewerClient;
import dev.erudites.mods.imageviewer.command.ImageViewerCommands;
import dev.erudites.mods.imageviewer.network.PayloadSender;
import dev.erudites.mods.imageviewer.network.payload.CatalogPayload;
import dev.erudites.mods.imageviewer.network.payload.ImageDataPayload;
import dev.erudites.mods.imageviewer.network.payload.ImageErrorPayload;
import dev.erudites.mods.imageviewer.network.payload.ImageRequestPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@Mod(ImageViewer.MODID)
public class ImageViewerNeoForgeMod {

    private static final PayloadSender SENDER = new PayloadSender() {
        @Override
        public Packet<?> packet(final CustomPacketPayload payload) {
            return new ClientboundCustomPayloadPacket(payload);
        }

        @Override
        public boolean canReceive(final ServerPlayer player) {
            return player.connection.hasChannel(CatalogPayload.TYPE);
        }
    };

    public ImageViewerNeoForgeMod(final IEventBus modBus) {
        modBus.addListener(this::registerPayloads);

        IEventBus gameBus = NeoForge.EVENT_BUS;
        gameBus.addListener((ServerStartedEvent event) -> ImageViewer.start());
        gameBus.addListener((ServerStoppingEvent event) -> ImageViewer.stop());
        gameBus.addListener(this::playerJoin);
        gameBus.addListener(this::playerLeave);
        gameBus.addListener(this::registerCommands);
    }

    private void registerPayloads(final RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(ImageViewer.MODID).optional();
        registrar.playToClient(
            CatalogPayload.TYPE,
            CatalogPayload.CODEC,
            (payload, context) -> ImageViewerClient.onCatalog(Minecraft.getInstance(), payload)
        );
        registrar.playToClient(
            ImageDataPayload.TYPE,
            ImageDataPayload.CODEC,
            (payload, context) -> ImageViewerClient.onData(Minecraft.getInstance(), payload)
        );
        registrar.playToClient(
            ImageErrorPayload.TYPE,
            ImageErrorPayload.CODEC,
            (payload, context) -> ImageViewerClient.onError(Minecraft.getInstance(), payload)
        );
        registrar.playToServer(
            ImageRequestPayload.TYPE,
            ImageRequestPayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    ImageViewer.handleRequest(player, payload.entries(), SENDER);
                }
            })
        );
    }

    private void playerJoin(final PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ImageViewer.sendCatalog(player, SENDER);
        }
    }

    private void playerLeave(final PlayerEvent.PlayerLoggedOutEvent event) {
        ImageViewer.onPlayerLeave(event.getEntity().getUUID());
    }

    private void registerCommands(final RegisterCommandsEvent event) {
        event.getDispatcher().register(ImageViewerCommands.reloadCommand(SENDER));
    }
}
