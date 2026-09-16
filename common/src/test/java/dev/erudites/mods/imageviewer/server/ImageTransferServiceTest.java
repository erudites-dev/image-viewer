package dev.erudites.mods.imageviewer.server;

import dev.erudites.mods.imageviewer.network.ImageHashes;
import dev.erudites.mods.imageviewer.network.payload.ImageDataPayload;
import dev.erudites.mods.imageviewer.network.payload.ImageRequestPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageTransferServiceTest {

    @TempDir
    Path directory;

    @Test
    @Timeout(10)
    void deliversImageFromBackgroundThread() throws Exception {
        byte[] content = new byte[ImageDataPayload.MAX_CHUNK_SIZE * 3 + 17];
        new Random(1).nextBytes(content);
        Files.write(this.directory.resolve("1.png"), content);
        String hash = ImageHashes.sha256(content);
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        CountDownLatch done = new CountDownLatch(1);

        try (ImageTransferService service = this.service()) {
            service.request(UUID.randomUUID(), (payload, onWritten) -> {
                this.collect(received, payload, content.length, done);
                onWritten.run();
            }, List.of(new ImageRequestPayload.Entry(hash, 1)));

            assertTrue(done.await(5, TimeUnit.SECONDS));
        }

        assertArrayEquals(content, received.toByteArray());
    }

    @Test
    @Timeout(10)
    void ignoresRequestsAfterClose() throws IOException {
        byte[] content = new byte[100];
        Files.write(this.directory.resolve("1.png"), content);
        AtomicInteger sends = new AtomicInteger();
        ImageTransferService service = this.service();

        service.close();
        service.request(UUID.randomUUID(), (payload, onWritten) -> sends.incrementAndGet(), List.of(
            new ImageRequestPayload.Entry(ImageHashes.sha256(content), 1)
        ));
        service.remove(UUID.randomUUID());
        service.close();

        assertEquals(0, sends.get());
    }

    private ImageTransferService service() {
        return new ImageTransferService(ImageCatalog.scan(this.directory, 64 * 1024 * 1024, ImageCatalog.empty()), 0);
    }

    private void collect(final ByteArrayOutputStream received, final CustomPacketPayload payload, final int total, final CountDownLatch done) {
        if (payload instanceof ImageDataPayload data) {
            synchronized (received) {
                received.writeBytes(data.data());
                if (received.size() == total) {
                    done.countDown();
                }
            }
        }
    }
}
