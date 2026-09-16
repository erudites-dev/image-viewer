package dev.erudites.mods.imageviewer.network.payload;

import dev.erudites.mods.imageviewer.ImageViewer;
import dev.erudites.mods.imageviewer.network.ImageHashes;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ImageDataPayload(String hash, int ticket, int offset, int totalSize, byte[] data) implements CustomPacketPayload {

    public static final int MAX_CHUNK_SIZE = 512 * 1024;

    public static final Type<ImageDataPayload> TYPE = new Type<>(ImageViewer.payloadId("data"));

    public static final StreamCodec<ByteBuf, ImageDataPayload> CODEC = StreamCodec.composite(
        ByteBufCodecs.stringUtf8(ImageHashes.HEX_LENGTH),
        ImageDataPayload::hash,
        ByteBufCodecs.VAR_INT,
        ImageDataPayload::ticket,
        ByteBufCodecs.VAR_INT,
        ImageDataPayload::offset,
        ByteBufCodecs.VAR_INT,
        ImageDataPayload::totalSize,
        ByteBufCodecs.byteArray(MAX_CHUNK_SIZE),
        ImageDataPayload::data,
        ImageDataPayload::new
    );

    @Override
    public Type<ImageDataPayload> type() {
        return TYPE;
    }
}
