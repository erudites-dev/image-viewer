package dev.erudites.mods.imageviewer.network.payload;

import dev.erudites.mods.imageviewer.ImageViewer;
import dev.erudites.mods.imageviewer.network.ImageHashes;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ImageErrorPayload(String hash, int ticket, Reason reason) implements CustomPacketPayload {

    public enum Reason {
        NOT_FOUND,
        IO_ERROR;

        private static final Reason[] VALUES = values();

        static Reason byId(final int id) {
            return id >= 0 && id < VALUES.length ? VALUES[id] : IO_ERROR;
        }
    }

    public static final Type<ImageErrorPayload> TYPE = new Type<>(ImageViewer.payloadId("error"));

    public static final StreamCodec<ByteBuf, ImageErrorPayload> CODEC = StreamCodec.composite(
        ByteBufCodecs.stringUtf8(ImageHashes.HEX_LENGTH),
        ImageErrorPayload::hash,
        ByteBufCodecs.VAR_INT,
        ImageErrorPayload::ticket,
        ByteBufCodecs.VAR_INT.map(Reason::byId, Reason::ordinal),
        ImageErrorPayload::reason,
        ImageErrorPayload::new
    );

    @Override
    public Type<ImageErrorPayload> type() {
        return TYPE;
    }
}
