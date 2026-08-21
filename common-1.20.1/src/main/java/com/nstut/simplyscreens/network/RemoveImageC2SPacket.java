package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.function.Supplier;

public class RemoveImageC2SPacket {
    private final UUID imageId;

    public RemoveImageC2SPacket(UUID imageId) {
        this.imageId = imageId;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(imageId);
    }

    public static RemoveImageC2SPacket read(FriendlyByteBuf buf) {
        return new RemoveImageC2SPacket(buf.readUUID());
    }

    public static void apply(RemoveImageC2SPacket msg, Supplier<NetworkManager.PacketContext> context) {
        ServerPlayer player = (ServerPlayer) context.get().getPlayer();
        if (player == null) return;
        context.get().queue(() -> {
            boolean isAdmin = player.hasPermissions(2);
            boolean deleted = ServerImageManager.deleteImage(player.getServer(), msg.imageId, player.getUUID().toString(), isAdmin);
            if (deleted && player.getServer() != null) {
                for (ServerPlayer onlinePlayer : player.getServer().getPlayerList().getPlayers()) {
                    var images = ServerImageManager.getImageListForPlayer(player.getServer(), onlinePlayer.getUUID().toString());
                    PacketRegistries.sendToPlayer(onlinePlayer, new UpdateImageListS2CPacket(images));
                }
            }
        });
    }
}
