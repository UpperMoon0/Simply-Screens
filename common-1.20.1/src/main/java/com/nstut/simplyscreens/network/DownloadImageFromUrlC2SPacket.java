package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.Config;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import com.nstut.simplyscreens.helpers.UrlSecurity;
import com.nstut.simplyscreens.helpers.ScreenPacketSecurity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.function.Supplier;
import dev.architectury.networking.NetworkManager;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

public class DownloadImageFromUrlC2SPacket {
    private final BlockPos blockPos;
    private final String url;
    private final String fileName;

    public DownloadImageFromUrlC2SPacket(BlockPos blockPos, String url, String fileName) {
        this.blockPos = blockPos;
        this.url = url;
        this.fileName = fileName;
    }

    public DownloadImageFromUrlC2SPacket(FriendlyByteBuf buf) {
        this.blockPos = buf.readBlockPos();
        this.url = buf.readUtf();
        this.fileName = buf.readUtf();
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(blockPos);
        buf.writeUtf(url);
        buf.writeUtf(fileName);
    }

    public void handle(Supplier<NetworkManager.PacketContext> context) {
        context.get().queue(() -> {
            ServerPlayer player = (ServerPlayer) context.get().getPlayer();
            if (player == null || Config.DISABLE_URL_DOWNLOAD || !ScreenPacketSecurity.canModify(player, blockPos)) return;
            if (player == null) {
                return;
            }

            // Validate URL
            if (url == null || url.isEmpty()) {
                SimplyScreens.LOGGER.warn("Received empty URL from player {}", player.getName().getString());
                return;
            }

            // Basic URL validation
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                SimplyScreens.LOGGER.warn("Invalid URL protocol from player {}: {}", player.getName().getString(), url);
                return;
            }

            // Download image asynchronously
            CompletableFuture.runAsync(() -> {
                try {
                    HttpClient client = HttpClient.newHttpClient();
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(UrlSecurity.requirePublicHttpUrl(url))
                            .timeout(java.time.Duration.ofSeconds(30))
                            .build();

                    HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

                    if (response.statusCode() == 200) {
                        byte[] imageData;
                        try (InputStream body = response.body()) {
                            imageData = body.readNBytes(Config.MAX_URL_DOWNLOAD_SIZE + 1);
                        }
                        if (imageData == null || imageData.length == 0) {
                            SimplyScreens.LOGGER.warn("Empty image data received from URL: {}", url);
                            return;
                        }

                        // Check file size limit
                        if (imageData.length > Config.MAX_URL_DOWNLOAD_SIZE) {
                            SimplyScreens.LOGGER.warn("Image from URL {} exceeds maximum size: {} bytes (max: {})", url, imageData.length, Config.MAX_URL_DOWNLOAD_SIZE);
                            return;
                        }

                        // Save the image using ServerImageManager
                        String fname = fileName != null ? fileName : "url_image";
                        var imageId = ServerImageManager.saveImage(player.getServer(), fname, imageData, null, player.getUUID().toString());
                        if (imageId != null) {
                            SimplyScreens.LOGGER.info("Successfully downloaded image from URL: {} (ID: {})", url, imageId);

                            // Set the image on the screen block
                            player.getServer().execute(() -> {
                                ServerLevel level = player.serverLevel();
                                BlockEntity blockEntity = level.getBlockEntity(blockPos);
                                if (blockEntity instanceof com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity screen) {
                                    var anchor = screen.getAnchorEntity();
                                    if (anchor != null) {
                                        anchor.setImageId(imageId);
                                    }
                                }

                            // Send updated image list to player
                            var images = ServerImageManager.getImageListForPlayer(player.getServer(), player.getUUID().toString());
                            PacketRegistries.CHANNEL.sendToPlayer(player, new UpdateImageListS2CPacket(images));
                            });
                        } else {
                            player.getServer().execute(() -> {
                                player.displayClientMessage(Component.literal("§c[Simply Screens] Failed to load image from URL: the content is not a valid image or is in an unsupported format."), false);
                            });
                        }
                    } else {
                        SimplyScreens.LOGGER.warn("Failed to download image from URL: {} - HTTP {}", url, response.statusCode());
                    }
                } catch (Exception e) {
                    SimplyScreens.LOGGER.error("Error downloading image from URL: {}", url, e);
                }
            });
        });
    }

}
