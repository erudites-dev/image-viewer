package dev.erudites.mods.imageviewer.client.screen;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import com.cinemamod.mcef.MCEFRenderer;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class ImageViewerScreen extends Screen {

    private final String url;
    private MCEFBrowser browser;

    public ImageViewerScreen(String url) {
        super(Component.empty());
        this.url = url;
    }

    private int guiScale() {
        return minecraft.getWindow().getGuiScale();
    }

    @Override
    protected void init() {
        super.init();
        if (MCEF.isInitialized()) {
            if (this.browser == null) {
                this.browser = MCEF.createBrowser(this.url, false);
            }
            this.resizeBrowser();
            this.browser.setFocus(true);
        }
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        if (this.browser != null) {
            this.resizeBrowser();
        }
    }

    private void resizeBrowser() {
        Window window = minecraft.getWindow();
        this.browser.resize(window.getWidth(), window.getHeight());
    }

    @Override
    public void tick() {
        super.tick();
        if (this.browser != null) {
            String currentUrl = this.browser.getURL();
            if (currentUrl != null && currentUrl.startsWith("imageviewer://close")) {
                this.onClose();
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (this.browser == null || !this.browser.isTextureReady()) {
            return;
        }
        MCEFRenderer renderer = this.browser.getRenderer();
        if (renderer == null || renderer.getTextureWidth() <= 1 || renderer.getTextureHeight() <= 1) {
            return;
        }
        Identifier textureLocation = this.browser.getTextureIdentifier();
        if (textureLocation == null) {
            return;
        }
        guiGraphics.blit(textureLocation, 0, 0, width, height, 0.0F, 1.0F, 0.0F, 1.0F);
    }

    @Override
    public void onClose() {
        if (this.browser != null) {
            this.browser.close();
            this.browser = null;
        }
        super.onClose();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (this.browser != null) {
            int s = guiScale();
            this.browser.sendMousePress((int) (event.x() * s), (int) (event.y() * s), event.button());
            this.browser.setFocus(true);
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.browser != null) {
            int scale = guiScale();
            this.browser.sendMouseRelease((int) (event.x() * scale), (int) (event.y() * scale), event.button());
            this.browser.setFocus(true);
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (this.browser != null) {
            int scale = guiScale();
            this.browser.sendMouseWheel((int) (mouseX * scale), (int) (mouseY * scale), verticalAmount, 0);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void mouseMoved(double x, double y) {
        if (this.browser != null) {
            int scale = guiScale();
            this.browser.sendMouseMove((int) (x * scale), (int) (y * scale));
        }
        super.mouseMoved(x, y);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        if (this.browser != null) {
            this.browser.sendKeyPress(event.key(), event.scancode(), event.modifiers());
            this.browser.setFocus(true);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (this.browser != null) {
            this.browser.sendKeyRelease(event.key(), event.scancode(), event.modifiers());
            this.browser.setFocus(true);
            return true;
        }
        return super.keyReleased(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (this.browser != null) {
            int codepoint = event.codepoint();
            if (codepoint != 0) {
                this.browser.sendKeyTyped((char) codepoint, 0);
                this.browser.setFocus(true);
            }
            return true;
        }
        return super.charTyped(event);
    }
}
