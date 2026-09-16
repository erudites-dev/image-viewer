package dev.erudites.mods.imageviewer.client.screen;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.erudites.mods.imageviewer.ImageViewer;
import dev.erudites.mods.imageviewer.client.ImageViewerClient;
import dev.erudites.mods.imageviewer.client.cache.ImageStore;
import dev.erudites.mods.imageviewer.client.texture.DecodedImage;
import dev.erudites.mods.imageviewer.client.texture.ImageDecoder;
import dev.erudites.mods.imageviewer.client.texture.ImageTexture;
import dev.erudites.mods.imageviewer.network.payload.CatalogPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

public class ImageViewerScreen extends Screen {

    private static final double MIN_ZOOM = 1.0;
    private static final double MAX_ZOOM = 16.0;
    private static final double ZOOM_STEP = 1.25;
    private static final double DRAG_THRESHOLD_PIXELS = 4.0;
    private static final long NEIGHBOR_GPU_BUDGET_BYTES = 1024L * 1024 * 1024;
    private static final int PREFETCH_MAX_IMAGES = 64;
    private static final long PREFETCH_MAX_BYTES = 256L * 1024 * 1024;
    private static final double BYTES_PER_MEBIBYTE = 1024.0 * 1024.0;
    private static final int NO_BUTTON = -1;

    private static final class Slot {
        private final int index;
        private volatile boolean cancelled;
        private volatile long decodeNanos;
        private @Nullable ImageTexture texture;
        private boolean failed;

        private Slot(final int index) {
            this.index = index;
        }

        private void release() {
            this.cancelled = true;
            if (this.texture != null) {
                this.texture.close();
                this.texture = null;
            }
        }
    }

    private record Bounds(int x0, int y0, int x1, int y1) {}

    private final List<CatalogPayload.Entry> images;
    private final boolean keepAspectRatio;
    private final Map<Integer, Slot> slots = new HashMap<>();

    private int index;
    private int maxTextureSize;
    private boolean active;

    private double zoom = MIN_ZOOM;
    private double panX;
    private double panY;

    private int pressedButton = NO_BUTTON;
    private double pressX;
    private double pressY;
    private boolean dragging;

    public ImageViewerScreen(final List<CatalogPayload.Entry> images, final boolean keepAspectRatio) {
        super(Component.empty());
        this.images = List.copyOf(images);
        this.keepAspectRatio = keepAspectRatio;
    }

    @Override
    public void added() {
        super.added();
        this.active = true;
    }

    @Override
    protected void init() {
        super.init();
        this.maxTextureSize = RenderSystem.maxSupportedTextureSize();
        if (this.active && !this.slots.containsKey(this.index)) {
            this.show(this.index);
        }
        this.clampPan();
    }

    @Override
    public void removed() {
        this.active = false;
        for (Slot slot : this.slots.values()) {
            slot.release();
        }
        this.slots.clear();
        ImageViewerClient.store(this.minecraft).prioritize(List.of());
        super.removed();
    }

    private void show(final int target) {
        if (this.images.isEmpty()) {
            return;
        }
        this.index = Math.clamp(target, 0, this.images.size() - 1);
        this.resetView();

        List<Integer> stale = new ArrayList<>();
        for (Map.Entry<Integer, Slot> entry : this.slots.entrySet()) {
            if (Math.abs(entry.getKey() - this.index) > 1 || entry.getValue().failed) {
                stale.add(entry.getKey());
            }
        }
        for (Integer slotIndex : stale) {
            this.slots.remove(slotIndex).release();
        }

        this.load(this.index);
        this.load(this.index + 1);
        this.load(this.index - 1);
        this.prioritize();
    }

    private void prioritize() {
        List<CatalogPayload.Entry> wanted = new ArrayList<>();
        for (int slotIndex : new int[] {this.index, this.index + 1, this.index - 1}) {
            if (slotIndex >= 0 && slotIndex < this.images.size()) {
                wanted.add(this.images.get(slotIndex));
            }
        }
        long prefetchBytes = 0;
        for (int ahead = this.index + 2; ahead < this.images.size() && wanted.size() < PREFETCH_MAX_IMAGES; ahead++) {
            CatalogPayload.Entry entry = this.images.get(ahead);
            prefetchBytes += entry.size();
            if (prefetchBytes > PREFETCH_MAX_BYTES) {
                break;
            }
            wanted.add(entry);
        }
        ImageViewerClient.store(this.minecraft).prioritize(wanted);
    }

    private void load(final int slotIndex) {
        if (slotIndex < 0 || slotIndex >= this.images.size() || this.slots.containsKey(slotIndex)) {
            return;
        }
        Slot slot = new Slot(slotIndex);
        this.slots.put(slotIndex, slot);

        CatalogPayload.Entry entry = this.images.get(slotIndex);
        int textureSize = this.maxTextureSize;
        ImageViewerClient.store(this.minecraft)
            .fetch(entry)
            .thenApplyAsync(bytes -> slot.cancelled ? null : decode(slot, bytes, textureSize), ImageViewerClient.decodeExecutor())
            .whenCompleteAsync((decoded, error) -> this.onDecoded(slot, entry, decoded, error), this.minecraft);
    }

    private static DecodedImage decode(final Slot slot, final byte[] bytes, final int maxTextureSize) {
        long start = System.nanoTime();
        try {
            return ImageDecoder.decode(bytes, maxTextureSize);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            slot.decodeNanos = System.nanoTime() - start;
        }
    }

    private void onDecoded(final Slot slot, final CatalogPayload.Entry entry, final @Nullable DecodedImage decoded, final @Nullable Throwable error) {
        try {
            if (!this.active || slot.cancelled || this.slots.get(slot.index) != slot) {
                return;
            }
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                if (!(cause instanceof CancellationException)) {
                    ImageViewer.LOGGER.warn("Failed to display image {}", entry.hash(), cause);
                }
                slot.failed = true;
                return;
            }
            if (decoded == null) {
                return;
            }
            if (slot.index != this.index && this.gpuBytes() + decoded.byteSize() > NEIGHBOR_GPU_BUDGET_BYTES) {
                this.slots.remove(slot.index);
                return;
            }
            long uploadStart = System.nanoTime();
            slot.texture = ImageTexture.upload(decoded);
            if (ImageStore.LOG_TIMINGS) {
                ImageViewer.LOGGER.info(
                    "[timings] {} ({}x{}, {} tiles) decode+mipmap {} ms, upload {} ms",
                    entry.hash().substring(0, 12),
                    decoded.width(),
                    decoded.height(),
                    decoded.tiles().size(),
                    TimeUnit.NANOSECONDS.toMillis(slot.decodeNanos),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - uploadStart)
                );
            }
        } catch (RuntimeException e) {
            ImageViewer.LOGGER.warn("Failed to upload image {}", entry.hash(), e);
            slot.failed = true;
        } finally {
            if (decoded != null) {
                decoded.close();
            }
        }
    }

    private long gpuBytes() {
        long total = 0;
        for (Slot slot : this.slots.values()) {
            if (slot.texture != null) {
                total += slot.texture.byteSize();
            }
        }
        return total;
    }

    private void next() {
        if (this.index < this.images.size() - 1) {
            this.show(this.index + 1);
        } else {
            this.onClose();
        }
    }

    private void previous() {
        if (this.index > 0) {
            this.show(this.index - 1);
        }
    }

    private void resetView() {
        this.zoom = MIN_ZOOM;
        this.panX = 0.0;
        this.panY = 0.0;
        this.dragging = false;
        this.pressedButton = NO_BUTTON;
    }

    @Override
    public void renderBackground(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xFF000000);
    }

    @Override
    public void render(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        Slot slot = this.slots.get(this.index);
        if (slot != null && slot.texture != null) {
            float scale = (float) this.minecraft.getWindow().getGuiScale();
            Bounds bounds = this.imageBounds(slot.texture);
            graphics.pose().pushPose();
            graphics.pose().scale(1.0F / scale, 1.0F / scale, 1.0F);
            slot.texture.draw(graphics, bounds.x0(), bounds.y0(), bounds.x1(), bounds.y1());
            graphics.pose().popPose();
            return;
        }

        graphics.drawCenteredString(this.font, this.statusMessage(slot), this.width / 2, this.height / 2, 0xFFFFFF);
    }

    private Component statusMessage(final @Nullable Slot slot) {
        if (slot != null && slot.failed) {
            return Component.translatable("screen.imageviewer.failed");
        }
        if (this.index < this.images.size()) {
            ImageStore.Progress progress = ImageViewerClient.store(this.minecraft).progress(this.images.get(this.index).hash());
            if (progress != null && progress.receivedBytes() > 0) {
                return Component.translatable(
                    "screen.imageviewer.downloading",
                    progress.receivedBytes() * 100L / progress.totalBytes(),
                    String.format(Locale.ROOT, "%.1f", progress.receivedBytes() / BYTES_PER_MEBIBYTE),
                    String.format(Locale.ROOT, "%.1f", progress.totalBytes() / BYTES_PER_MEBIBYTE)
                );
            }
        }
        return Component.translatable("screen.imageviewer.loading");
    }

    private Bounds imageBounds(final ImageTexture texture) {
        Window window = this.minecraft.getWindow();
        double screenWidth = window.getWidth();
        double screenHeight = window.getHeight();
        double baseWidth = screenWidth;
        double baseHeight = screenHeight;
        if (this.keepAspectRatio) {
            double fit = Math.min(screenWidth / texture.width(), screenHeight / texture.height());
            baseWidth = texture.width() * fit;
            baseHeight = texture.height() * fit;
        }
        double width = baseWidth * this.zoom;
        double height = baseHeight * this.zoom;
        double x0 = (screenWidth - width) / 2.0 + this.panX;
        double y0 = (screenHeight - height) / 2.0 + this.panY;
        return new Bounds(
            (int) Math.round(x0),
            (int) Math.round(y0),
            (int) Math.round(x0 + width),
            (int) Math.round(y0 + height)
        );
    }

    private void clampPan() {
        Slot slot = this.slots.get(this.index);
        if (slot == null || slot.texture == null) {
            this.panX = 0.0;
            this.panY = 0.0;
            return;
        }
        Window window = this.minecraft.getWindow();
        Bounds centered = this.imageBounds(slot.texture);
        double width = centered.x1() - centered.x0();
        double height = centered.y1() - centered.y0();
        double limitX = Math.max(0.0, (width - window.getWidth()) / 2.0);
        double limitY = Math.max(0.0, (height - window.getHeight()) / 2.0);
        this.panX = Math.clamp(this.panX, -limitX, limitX);
        this.panY = Math.clamp(this.panY, -limitY, limitY);
    }

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        this.pressedButton = button;
        this.pressX = mouseX;
        this.pressY = mouseY;
        this.dragging = false;
        return true;
    }

    @Override
    public boolean mouseDragged(final double mouseX, final double mouseY, final int button, final double dragX, final double dragY) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || this.pressedButton != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return true;
        }
        double scale = this.minecraft.getWindow().getGuiScale();
        if (!this.dragging) {
            double distance = Math.hypot(mouseX - this.pressX, mouseY - this.pressY) * scale;
            this.dragging = distance > DRAG_THRESHOLD_PIXELS;
        }
        if (this.dragging) {
            this.panX += dragX * scale;
            this.panY += dragY * scale;
            this.clampPan();
        }
        return true;
    }

    @Override
    public boolean mouseReleased(final double mouseX, final double mouseY, final int button) {
        if (button != this.pressedButton) {
            return true;
        }
        boolean wasDragging = this.dragging;
        this.pressedButton = NO_BUTTON;
        this.dragging = false;
        if (wasDragging) {
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            this.next();
        } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            this.previous();
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double scrollX, final double scrollY) {
        Slot slot = this.slots.get(this.index);
        if (slot == null || slot.texture == null || scrollY == 0.0) {
            return true;
        }
        Window window = this.minecraft.getWindow();
        double scale = window.getGuiScale();
        double newZoom = Math.clamp(this.zoom * Math.pow(ZOOM_STEP, scrollY), MIN_ZOOM, MAX_ZOOM);
        double ratio = newZoom / this.zoom;
        double offsetX = mouseX * scale - (window.getWidth() / 2.0 + this.panX);
        double offsetY = mouseY * scale - (window.getHeight() / 2.0 + this.panY);
        this.panX += offsetX - offsetX * ratio;
        this.panY += offsetY - offsetY * ratio;
        this.zoom = newZoom;
        this.clampPan();
        return true;
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_PAGE_DOWN -> {
                this.next();
                return true;
            }
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_PAGE_UP -> {
                this.previous();
                return true;
            }
            case GLFW.GLFW_KEY_0, GLFW.GLFW_KEY_KP_0 -> {
                this.resetView();
                return true;
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }
}
