package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class RemoveImageC2SPacket implements CustomPacketPayload {
    public static final Type<RemoveImageC2SPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SimplyScreens.MOD_ID, "remove_image_c2s"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RemoveImageC2SPacket> CODEC =
            StreamCodec.ofMember(RemoveImageC2SPacket::write, RemoveImageC2SPacket::new);

    private final UUID imageId;

    public RemoveImageC2SPacket(UUID imageId) {
        this.imageId = imageId;
    }

    public RemoveImageC2SPacket(RegistryFriendlyByteBuf buf) {
        this.imageId = buf.readUUID();
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(imageId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RemoveImageC2SPacket packet, NetworkManager.PacketContext context) {
        ServerPlayer player = (ServerPlayer) context.getPlayer();
        if (player == null) return;
        context.queue(() -> {
            boolean isAdmin = player.hasPermissions(2);
            boolean deleted = ServerImageManager.deleteImage(player.getServer(), packet.imageId, player.getUUID().toString(), isAdmin);
            if (deleted && player.getServer() != null) {
                for (ServerPlayer onlinePlayer : player.getServer().getPlayerList().getPlayers()) {
                    var images = ServerImageManager.getImageListForPlayer(player.getServer(), onlinePlayer.getUUID().toString());
                    PacketRegistries.sendToPlayer(onlinePlayer, new UpdateImageListS2CPacket(images));
                }
            }
        });
    }
}
