package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class RequestImageDownloadC2SPacket implements IPacket {
    private static final Set<UUID> loggedWarnings = ConcurrentHashMap.newKeySet();
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
            com.nstut.simplyscreens.helpers.ImageMetadata metadata = ServerImageManager.getImageMetadata(player.getServer(), imageId);
            byte[] imageData = ServerImageManager.getImageData(player.getServer(), imageId);

            if (imageData != null && metadata != null) {
                PacketRegistries.CHANNEL.sendToPlayer(player, new ImageDownloadS2CPacket(imageId, metadata.getExtension(), imageData));
            } else {
                if (loggedWarnings.add(imageId)) {
                    SimplyScreens.LOGGER.warn("Player {} requested non-existent image {}. This warning will not be shown again.", player.getName().getString(), imageId);
                }
            }
        });
    }
}