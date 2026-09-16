package dev.erudites.mods.imageviewer.network.payload;

import dev.erudites.mods.imageviewer.ImageViewer;
import dev.erudites.mods.imageviewer.network.ImageHashes;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

public record ImageRequestPayload(List<Entry> entries) implements CustomPacketPayload {

    public static final int MAX_ENTRIES = 256;

    public static final Type<ImageRequestPayload> TYPE = new Type<>(ImageViewer.payloadId("request"));

    public record Entry(String hash, int ticket) {
        public static final StreamCodec<ByteBuf, Entry> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(ImageHashes.HEX_LENGTH),
            Entry::hash,
            ByteBufCodecs.VAR_INT,
            Entry::ticket,
            Entry::new
        );
    }

    public static final StreamCodec<ByteBuf, ImageRequestPayload> CODEC = StreamCodec.composite(
        Entry.CODEC.apply(ByteBufCodecs.list(MAX_ENTRIES)),
        ImageRequestPayload::entries,
        ImageRequestPayload::new
    );

    @Override
    public Type<ImageRequestPayload> type() {
        return TYPE;
    }
}
