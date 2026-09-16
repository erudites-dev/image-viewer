package dev.erudites.mods.imageviewer.network;

import dev.erudites.mods.imageviewer.network.payload.CatalogPayload;
import dev.erudites.mods.imageviewer.network.payload.ImageDataPayload;
import dev.erudites.mods.imageviewer.network.payload.ImageErrorPayload;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

@FunctionalInterface
public interface PayloadSender {

    Packet<?> packet(CustomPacketPayload payload);

    default boolean canReceive(final ServerPlayer player) {
        return true;
    }

    default void send(final ServerPlayer player, final CustomPacketPayload payload) {
        player.connection.send(this.packet(payload));
    }

    default void send(final ServerPlayer player, final CustomPacketPayload payload, final Runnable onWritten) {
        player.connection.send(this.packet(payload), PacketSendListener.thenRun(onWritten));
    }

    static void encode(final ByteBuf buf, final CustomPacketPayload payload) {
        switch (payload) {
            case CatalogPayload catalog -> CatalogPayload.CODEC.encode(buf, catalog);
            case ImageDataPayload data -> ImageDataPayload.CODEC.encode(buf, data);
            case ImageErrorPayload error -> ImageErrorPayload.CODEC.encode(buf, error);
            default -> throw new IllegalArgumentException("Unknown clientbound payload: " + payload.type().id());
        }
    }
}
