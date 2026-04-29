package dev.erudites.mods.imageviewer.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public class ImageViewerSelectionScreen extends Screen {

    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_SPACING = 4;

    private final List<String> categories;

    public ImageViewerSelectionScreen(List<String> categories) {
        super(Component.translatable("screen.imageviewer.select_category"));
        this.categories = categories;
    }

    @Override
    protected void init() {
        super.init();
        int totalHeight = this.categories.size() * (BUTTON_HEIGHT + BUTTON_SPACING) - BUTTON_SPACING;
        int startY = (height - totalHeight) / 2;
        int x = (width - BUTTON_WIDTH) / 2;

        for (int i = 0; i < this.categories.size(); i++) {
            String category = this.categories.get(i);
            int y = startY + i * (BUTTON_HEIGHT + BUTTON_SPACING);
            Component label = category.equals("main")
                ? Component.translatable("label.imageviewer.main")
                : Component.translatable("label.imageviewer.category", category);

            addRenderableWidget(Button.builder(label, _ -> {
                String url = buildUrl(category);
                minecraft.setScreen(new ImageViewerScreen(url));
            }).bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        }
    }

    @Override
    public boolean isInGameUi() {
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.centeredText(font, title, width / 2, 20, 0xFFFFFF);
    }

    private String buildUrl(String category) {
        return baseUrl + (category.equals("main") ? "" : category + "/");
    }

    public static String baseUrl = "";
}
