package dev.erudites.mods.imageviewer.paper;

import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.erudites.mods.imageviewer.ImageViewer;
import dev.erudites.mods.imageviewer.command.ImageViewerCommands;
import dev.erudites.mods.imageviewer.network.PayloadSender;
import dev.erudites.mods.imageviewer.network.payload.ImageRequestPayload;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class ImageViewerPlugin extends JavaPlugin implements Listener {

    private static final String REQUEST_CHANNEL = ImageRequestPayload.TYPE.id().toString();

    @Override
    public void onEnable() {
        ImageViewer.start();
        this.getServer().getPluginManager().registerEvents(this, this);
        this.getServer().getMessenger().registerIncomingPluginChannel(this, REQUEST_CHANNEL, this::onRequest);

        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            @SuppressWarnings({"unchecked", "rawtypes"})
            LiteralCommandNode<CommandSourceStack> node = (LiteralCommandNode) ImageViewerCommands.reloadCommand(this::packet).build();
            event.registrar().register(node, "Image Viewer admin command", List.of());
        });
    }

    @Override
    public void onDisable() {
        this.getServer().getMessenger().unregisterIncomingPluginChannel(this, REQUEST_CHANNEL);
        ImageViewer.stop();
    }

    @EventHandler
    public void onPlayerJoin(final PlayerJoinEvent event) {
        ImageViewer.sendCatalog(((CraftPlayer) event.getPlayer()).getHandle(), this::packet);
    }

    @EventHandler
    public void onPlayerQuit(final PlayerQuitEvent event) {
        ImageViewer.onPlayerLeave(event.getPlayer().getUniqueId());
    }

    private void onRequest(final String channel, final Player player, final byte[] message) {
        ByteBuf buf = Unpooled.wrappedBuffer(message);
        try {
            ImageRequestPayload payload = ImageRequestPayload.CODEC.decode(buf);
            ImageViewer.handleRequest(((CraftPlayer) player).getHandle(), payload.entries(), this::packet);
        } catch (RuntimeException e) {
            this.getSLF4JLogger().debug("Ignoring malformed image request from {}", player.getName(), e);
        } finally {
            buf.release();
        }
    }

    private Packet<?> packet(final CustomPacketPayload payload) {
        ByteBuf buf = Unpooled.buffer();
        try {
            PayloadSender.encode(buf, payload);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return new ClientboundCustomPayloadPacket(new DiscardedPayload(payload.type().id(), Unpooled.wrappedBuffer(bytes)));
        } finally {
            buf.release();
        }
    }
}
