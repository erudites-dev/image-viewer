package dev.erudites.mods.imageviewer.network;

import dev.erudites.mods.imageviewer.network.payload.CatalogPayload;
import dev.erudites.mods.imageviewer.network.payload.ImageDataPayload;
import dev.erudites.mods.imageviewer.network.payload.ImageErrorPayload;
import dev.erudites.mods.imageviewer.network.payload.ImageRequestPayload;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PayloadCodecTest {

    private static final String HASH = "a".repeat(ImageHashes.HEX_LENGTH);
    private static final String OTHER_HASH = "0123456789abcdef".repeat(4);

    @Test
    void catalogSurvivesRoundTrip() {
        CatalogPayload payload = new CatalogPayload(
            true,
            List.of(
                new CatalogPayload.Category(CatalogPayload.MAIN_CATEGORY, List.of(new CatalogPayload.Entry(HASH, 1234))),
                new CatalogPayload.Category("rules", List.of(
                    new CatalogPayload.Entry(OTHER_HASH, 1),
                    new CatalogPayload.Entry(HASH, 1234)
                ))
            )
        );

        assertEquals(payload, roundTrip(CatalogPayload.CODEC, payload));
    }

    @Test
    void requestSurvivesRoundTrip() {
        ImageRequestPayload payload = new ImageRequestPayload(List.of(
            new ImageRequestPayload.Entry(HASH, 1),
            new ImageRequestPayload.Entry(OTHER_HASH, Integer.MAX_VALUE)
        ));

        assertEquals(payload, roundTrip(ImageRequestPayload.CODEC, payload));
    }

    @Test
    void dataSurvivesRoundTrip() {
        byte[] data = new byte[ImageDataPayload.MAX_CHUNK_SIZE];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        ImageDataPayload payload = new ImageDataPayload(HASH, 7, 512, 4096, data);

        ImageDataPayload decoded = roundTrip(ImageDataPayload.CODEC, payload);

        assertEquals(payload.hash(), decoded.hash());
        assertEquals(payload.ticket(), decoded.ticket());
        assertEquals(payload.offset(), decoded.offset());
        assertEquals(payload.totalSize(), decoded.totalSize());
        assertArrayEquals(payload.data(), decoded.data());
    }

    @Test
    void dataRejectsOversizedChunk() {
        ByteBuf buf = Unpooled.buffer();
        try {
            ByteBufCodecs.stringUtf8(ImageHashes.HEX_LENGTH).encode(buf, HASH);
            ByteBufCodecs.VAR_INT.encode(buf, 0);
            ByteBufCodecs.VAR_INT.encode(buf, 0);
            ByteBufCodecs.VAR_INT.encode(buf, 0);
            ByteBufCodecs.BYTE_ARRAY.encode(buf, new byte[ImageDataPayload.MAX_CHUNK_SIZE + 1]);

            assertThrows(RuntimeException.class, () -> ImageDataPayload.CODEC.decode(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void errorSurvivesRoundTrip() {
        for (ImageErrorPayload.Reason reason : ImageErrorPayload.Reason.values()) {
            ImageErrorPayload payload = new ImageErrorPayload(HASH, 3, reason);

            assertEquals(payload, roundTrip(ImageErrorPayload.CODEC, payload));
        }
    }

    @Test
    void errorMapsUnknownReasonToIoError() {
        ByteBuf buf = Unpooled.buffer();
        try {
            ByteBufCodecs.stringUtf8(ImageHashes.HEX_LENGTH).encode(buf, HASH);
            ByteBufCodecs.VAR_INT.encode(buf, 1);
            ByteBufCodecs.VAR_INT.encode(buf, 99);

            assertEquals(ImageErrorPayload.Reason.IO_ERROR, ImageErrorPayload.CODEC.decode(buf).reason());
        } finally {
            buf.release();
        }
    }

    @Test
    void senderEncodesEveryClientboundPayloadLikeItsCodec() {
        CustomPacketPayload[] payloads = {
            new CatalogPayload(false, List.of()),
            new ImageDataPayload(HASH, 1, 0, 3, new byte[] {1, 2, 3}),
            new ImageErrorPayload(HASH, 1, ImageErrorPayload.Reason.NOT_FOUND)
        };

        for (CustomPacketPayload payload : payloads) {
            ByteBuf viaSender = Unpooled.buffer();
            ByteBuf viaCodec = Unpooled.buffer();
            try {
                PayloadSender.encode(viaSender, payload);
                encodeDirectly(viaCodec, payload);

                assertEquals(viaCodec, viaSender);
            } finally {
                viaSender.release();
                viaCodec.release();
            }
        }
    }

    @Test
    void payloadIdsCarryNetworkVersion() {
        assertEquals("imageviewer:catalog_v1", CatalogPayload.TYPE.id().toString());
        assertEquals("imageviewer:request_v1", ImageRequestPayload.TYPE.id().toString());
        assertEquals("imageviewer:data_v1", ImageDataPayload.TYPE.id().toString());
        assertEquals("imageviewer:error_v1", ImageErrorPayload.TYPE.id().toString());
    }

    @Test
    void senderRejectsServerboundPayload() {
        ByteBuf buf = Unpooled.buffer();
        try {
            assertThrows(IllegalArgumentException.class, () -> PayloadSender.encode(buf, new ImageRequestPayload(List.of())));
        } finally {
            buf.release();
        }
    }

    private static void encodeDirectly(final ByteBuf buf, final CustomPacketPayload payload) {
        if (payload instanceof CatalogPayload catalog) {
            CatalogPayload.CODEC.encode(buf, catalog);
        } else if (payload instanceof ImageDataPayload data) {
            ImageDataPayload.CODEC.encode(buf, data);
        } else if (payload instanceof ImageErrorPayload error) {
            ImageErrorPayload.CODEC.encode(buf, error);
        }
    }

    private static <T> T roundTrip(final StreamCodec<ByteBuf, T> codec, final T value) {
        ByteBuf buf = Unpooled.buffer();
        try {
            codec.encode(buf, value);
            T decoded = codec.decode(buf);
            assertEquals(0, buf.readableBytes());
            return decoded;
        } finally {
            buf.release();
        }
    }
}
