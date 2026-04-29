package dev.erudites.mods.imageviewer.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.erudites.mods.imageviewer.ImageViewer;
import dev.erudites.mods.imageviewer.network.OpenImagePayload;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

public final class ImageViewerCommands {

    private ImageViewerCommands() {}

    @FunctionalInterface
    public interface PlayerSender {
        void send(ServerPlayer player, OpenImagePayload payload);
    }

    public static int reloadAll(MinecraftServer server, PlayerSender sender) {
        OpenImagePayload payload = ImageViewer.buildPayload();
        if (payload == null) {
            return -1;
        }
        int count = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sender.send(player, payload);
            count++;
        }
        return count;
    }

    public static LiteralArgumentBuilder<CommandSourceStack> reloadCommand(PlayerSender sender) {
        return Commands.literal("imageviewer")
            .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
            .then(Commands.literal("reload").executes(ctx -> {
                int count = reloadAll(ctx.getSource().getServer(), sender);
                if (count < 0) {
                    ctx.getSource().sendFailure(Component.translatable("commands.imageviewer.reload.no_server"));
                    return 0;
                }
                final int sent = count;
                ctx.getSource().sendSuccess(
                    () -> Component.translatable("commands.imageviewer.reload.success", sent),
                    true
                );
                return Command.SINGLE_SUCCESS;
            }));
    }
}
