package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class RequestImageDownloadC2SPacket implements IPacket {
    private static final int CHUNK_SIZE = 1024 * 30; // 30KB
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
                String ext = metadata.getExtension();
                if (!"png".equals(ext)) {
                    try {
                        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
                        if (image != null) {
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            ImageIO.write(image, "png", baos);
                            imageData = baos.toByteArray();
                            ext = "png";
                        }
                    } catch (IOException e) {
                        SimplyScreens.LOGGER.warn("Could not convert image {} to PNG, sending original format", imageId);
                    }
                }

                int totalChunks = (int) Math.ceil((double) imageData.length / CHUNK_SIZE);
                for (int i = 0; i < totalChunks; i++) {
                    int start = i * CHUNK_SIZE;
                    int end = Math.min(imageData.length, start + CHUNK_SIZE);
                    byte[] chunk = new byte[end - start];
                    System.arraycopy(imageData, start, chunk, 0, chunk.length);

                    PacketRegistries.CHANNEL.sendToPlayer(player, new ImageDownloadChunkS2CPacket(imageId, i, totalChunks, chunk, i == 0 ? ext : null));
                }
            } else {
                if (loggedWarnings.add(imageId)) {
                    SimplyScreens.LOGGER.warn("Player {} requested non-existent image {}. This warning will not be shown again.", player.getName().getString(), imageId);
                }
            }
        });
    }
}