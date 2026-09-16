package dev.erudites.mods.imageviewer.client.screen;

import dev.erudites.mods.imageviewer.network.payload.CatalogPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public class ImageViewerSelectionScreen extends Screen {

    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_SPACING = 4;

    private final List<CatalogPayload.Category> categories;
    private final boolean keepAspectRatio;

    public ImageViewerSelectionScreen(final List<CatalogPayload.Category> categories, final boolean keepAspectRatio) {
        super(Component.translatable("screen.imageviewer.select_category"));
        this.categories = List.copyOf(categories);
        this.keepAspectRatio = keepAspectRatio;
    }

    @Override
    protected void init() {
        super.init();
        int totalHeight = this.categories.size() * (BUTTON_HEIGHT + BUTTON_SPACING) - BUTTON_SPACING;
        int startY = (this.height - totalHeight) / 2;
        int x = (this.width - BUTTON_WIDTH) / 2;

        for (int i = 0; i < this.categories.size(); i++) {
            CatalogPayload.Category category = this.categories.get(i);
            int y = startY + i * (BUTTON_HEIGHT + BUTTON_SPACING);
            Component label = category.isMain()
                ? Component.translatable("label.imageviewer.main")
                : Component.translatable("label.imageviewer.category", category.name());

            this.addRenderableWidget(Button.builder(label, button ->
                this.minecraft.setScreen(new ImageViewerScreen(category.images(), this.keepAspectRatio))
            ).bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        }
    }

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY, final float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFFFF);
    }
}
