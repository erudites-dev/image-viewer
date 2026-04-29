package dev.erudites.mods.imageviewer.paper;

import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.erudites.mods.imageviewer.ImageViewer;
import dev.erudites.mods.imageviewer.command.ImageViewerCommands;
import dev.erudites.mods.imageviewer.network.OpenImagePayload;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class ImageViewerPlugin extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        ImageViewer.startServer();
        this.getServer().getPluginManager().registerEvents(this, this);

        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            @SuppressWarnings({"unchecked", "rawtypes"})
            LiteralCommandNode<CommandSourceStack> node = (LiteralCommandNode) ImageViewerCommands.reloadCommand(this::sendPayload).build();
            event.registrar().register(node, "Image Viewer admin command", List.of());
        });
    }

    @Override
    public void onDisable() {
        ImageViewer.stopServer();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        OpenImagePayload payload = ImageViewer.buildPayload();
        if (payload == null) {
            return;
        }
        this.sendPayload(((CraftPlayer) event.getPlayer()).getHandle(), payload);
    }

    private void sendPayload(ServerPlayer player, OpenImagePayload payload) {
        ByteBuf buf = Unpooled.buffer();
        try {
            OpenImagePayload.CODEC.encode(buf, payload);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            player.connection.send(
                new ClientboundCustomPayloadPacket(new DiscardedPayload(OpenImagePayload.TYPE.id(), bytes))
            );
        } finally {
            buf.release();
        }
    }
}
