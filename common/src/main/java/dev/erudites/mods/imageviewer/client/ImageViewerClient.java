package dev.erudites.mods.imageviewer.client;

import dev.erudites.mods.imageviewer.ImageViewer;
import dev.erudites.mods.imageviewer.client.screen.ImageViewerScreen;
import dev.erudites.mods.imageviewer.client.screen.ImageViewerSelectionScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public final class ImageViewerClient {

    private static String lastBaseUrl = null;
    private static List<String> lastCategories = null;

    public static final KeyMapping OPEN_KEY = new KeyMapping(
        "key.imageviewer.open",
        GLFW.GLFW_KEY_I,
        KeyMapping.Category.register(ImageViewer.id("imageviewer"))
    );

    private ImageViewerClient() {}

    public static void openImagePayload(Minecraft minecraft, int port, List<String> categories) {
        minecraft.execute(() -> {
            lastBaseUrl = resolveBaseUrl(minecraft, port);
            lastCategories = categories;
        });
    }

    public static void tick(Minecraft client) {
        while (OPEN_KEY.consumeClick()) {
            if (lastBaseUrl != null && lastCategories != null && client.gui.screen() == null) {
                openViewer(client, lastBaseUrl, lastCategories);
            }
        }
    }

    private static void openViewer(Minecraft minecraft, String baseUrl, List<String> categories) {
        if (categories.isEmpty()) {
            minecraft.gui.setScreen(new ImageViewerScreen(baseUrl));
        } else if (categories.size() == 1) {
            String cat = categories.getFirst();
            String url = cat.equals("main") ? baseUrl : baseUrl + cat + "/";
            minecraft.gui.setScreen(new ImageViewerScreen(url));
        } else {
            ImageViewerSelectionScreen.baseUrl = baseUrl;
            minecraft.gui.setScreen(new ImageViewerSelectionScreen(categories));
        }
    }

    private static String resolveBaseUrl(Minecraft mc, int port) {
        String serverIp;
        if (mc.hasSingleplayerServer()) {
            serverIp = "localhost";
        } else {
            ServerData serverData = mc.getCurrentServer();
            if (serverData == null) {
                serverIp = "localhost";
            } else {
                String rawIp = serverData.ip;
                int lastColon = rawIp.lastIndexOf(':');
                if (lastColon > 0 && !rawIp.startsWith("[")) {
                    rawIp = rawIp.substring(0, lastColon);
                } else if (rawIp.startsWith("[")) {
                    int closeBracket = rawIp.indexOf(']');
                    rawIp = rawIp.substring(1, closeBracket);
                }
                serverIp = rawIp;
            }
        }
        return "http://" + serverIp + ":" + port + "/";
    }
}
