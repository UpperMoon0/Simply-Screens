package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.helpers.ImageMetadata;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RequestImageDownloadC2SPacket implements CustomPacketPayload {
    private static final int CHUNK_SIZE = 32768;
    private static final ExecutorService IMAGE_DOWNLOAD_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "Simply Screens Image Download");
        thread.setDaemon(true);
        return thread;
    });

    public static final Type<RequestImageDownloadC2SPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SimplyScreens.MOD_ID, "request_image_download_c2s"));
    
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestImageDownloadC2SPacket> CODEC = 
            StreamCodec.ofMember(RequestImageDownloadC2SPacket::write, RequestImageDownloadC2SPacket::new);

    private final UUID imageId;

    public RequestImageDownloadC2SPacket(UUID imageId) {
        this.imageId = imageId;
    }

    public RequestImageDownloadC2SPacket(RegistryFriendlyByteBuf buf) {
        this.imageId = buf.readUUID();
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(imageId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public UUID getImageId() {
        return imageId;
    }

    public static void handle(RequestImageDownloadC2SPacket packet, NetworkManager.PacketContext context) {
        ServerPlayer player = (ServerPlayer) context.getPlayer();
        context.queue(() -> {
            MinecraftServer server = player.level().getServer();
            IMAGE_DOWNLOAD_EXECUTOR.execute(() -> sendImageDownload(server, player, packet.imageId));
        });
    }

    private static void sendImageDownload(MinecraftServer server, ServerPlayer player, UUID imageId) {
        byte[] imageData = ServerImageManager.getImageData(server, imageId);
        if (imageData == null) {
            return;
        }

        ImageMetadata metadata = ServerImageManager.getImageMetadata(server, imageId);
        if (metadata == null) {
            return;
        }

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

        byte[] finalImageData = imageData;
        String finalExt = ext;
        server.execute(() -> sendImageChunks(player, imageId, finalImageData, finalExt));
    }

    private static void sendImageChunks(ServerPlayer player, UUID imageId, byte[] imageData, String ext) {
        int totalChunks = (int) Math.ceil((double) imageData.length / CHUNK_SIZE);

        for (int i = 0; i < totalChunks; i++) {
            int start = i * CHUNK_SIZE;
            int end = Math.min(start + CHUNK_SIZE, imageData.length);
            byte[] chunk = new byte[end - start];
            System.arraycopy(imageData, start, chunk, 0, chunk.length);

            PacketRegistries.sendToPlayer(player, new ImageDownloadChunkS2CPacket(
                    imageId, i, totalChunks, chunk,
                    i == 0 ? ext : null));
        }
    }
}


