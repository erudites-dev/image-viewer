package dev.erudites.mods.imageviewer.network.payload;

import dev.erudites.mods.imageviewer.ImageViewer;
import dev.erudites.mods.imageviewer.network.ImageHashes;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

public record CatalogPayload(boolean keepAspectRatio, List<Category> categories) implements CustomPacketPayload {
    public static final String MAIN_CATEGORY = "main";

    private static final int MAX_CATEGORIES = 256;
    private static final int MAX_IMAGES_PER_CATEGORY = 4096;
    private static final int MAX_NAME_LENGTH = 256;

    public static final Type<CatalogPayload> TYPE = new Type<>(ImageViewer.payloadId("catalog"));

    public record Entry(String hash, int size) {
        public static final StreamCodec<ByteBuf, Entry> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(ImageHashes.HEX_LENGTH),
            Entry::hash,
            ByteBufCodecs.VAR_INT,
            Entry::size,
            Entry::new
        );
    }

    public record Category(String name, List<Entry> images) {
        public static final StreamCodec<ByteBuf, Category> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH),
            Category::name,
            Entry.CODEC.apply(ByteBufCodecs.list(MAX_IMAGES_PER_CATEGORY)),
            Category::images,
            Category::new
        );

        public boolean isMain() {
            return MAIN_CATEGORY.equals(this.name);
        }
    }

    public static final StreamCodec<ByteBuf, CatalogPayload> CODEC = StreamCodec.composite(
        ByteBufCodecs.BOOL,
        CatalogPayload::keepAspectRatio,
        Category.CODEC.apply(ByteBufCodecs.list(MAX_CATEGORIES)),
        CatalogPayload::categories,
        CatalogPayload::new
    );

    @Override
    public Type<CatalogPayload> type() {
        return TYPE;
    }
}
