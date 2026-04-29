package dev.erudites.mods.imageviewer.network;

import dev.erudites.mods.imageviewer.ImageViewer;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

public record OpenImagePayload(int port, List<String> categories) implements CustomPacketPayload {

    public static final Type<OpenImagePayload> TYPE = new Type<>(ImageViewer.id("open_image"));

    public static final StreamCodec<ByteBuf, OpenImagePayload> CODEC = StreamCodec.composite(
        ByteBufCodecs.INT,
        OpenImagePayload::port,
        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()),
        OpenImagePayload::categories,
        OpenImagePayload::new
    );

    @Override
    public Type<OpenImagePayload> type() {
        return TYPE;
    }
}
