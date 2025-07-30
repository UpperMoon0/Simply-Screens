package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Supplier;
import java.util.UUID;

public class RequestImageDownloadC2SPacket implements IPacket {
    private final UUID imageId;

    public RequestImageDownloadC2SPacket(UUID imageId) {
        this.imageId = imageId;
    }

    public RequestImageDownloadC2SPacket(FriendlyByteBuf buf) {
        this.imageId = buf.readUUID();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(imageId);
    }

    @Override
    public void handle(Supplier<NetworkManager.PacketContext> context) {
        context.get().queue(() -> {
            ServerPlayer player = (ServerPlayer) context.get().getPlayer();
            byte[] imageData = ServerImageManager.getImageData(player.getServer(), imageId);

            if (imageData != null) {
                PacketRegistries.CHANNEL.sendToPlayer(player, new ImageDownloadS2CPacket(imageId, imageData));
            } else {
                SimplyScreens.LOGGER.warn("Player {} requested non-existent image {}", player.getName().getString(), imageId);
            }
        });
    }
}