package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.Config;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.function.Supplier;
import dev.architectury.networking.NetworkManager;

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
                            .uri(URI.create(url))
                            .timeout(java.time.Duration.ofSeconds(30))
                            .build();

                    HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

                    if (response.statusCode() == 200) {
                        byte[] imageData = response.body();
                        if (imageData == null || imageData.length == 0) {
                            SimplyScreens.LOGGER.warn("Empty image data received from URL: {}", url);
                            return;
                        }

                        // Check file size limit
                        if (imageData.length > Config.MAX_URL_DOWNLOAD_SIZE) {
                            SimplyScreens.LOGGER.warn("Image from URL {} exceeds maximum size: {} bytes (max: {})", url, imageData.length, Config.MAX_URL_DOWNLOAD_SIZE);
                            return;
                        }

                        // Determine file extension from content type or URL
                        String contentType = response.headers().firstValue("Content-Type").orElse("image/png");
                        String extension = getExtensionFromContentType(contentType, url);
                        String fname = fileName != null ? fileName : "url_image." + extension;

                        // Save the image using ServerImageManager
                        var imageId = ServerImageManager.saveImage(player.getServer(), fname, imageData, contentType, player.getUUID().toString());
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

    private static String getExtensionFromContentType(String contentType, String url) {
        if (contentType != null) {
            if (contentType.contains("png")) return "png";
            if (contentType.contains("jpeg") || contentType.contains("jpg")) return "jpg";
            if (contentType.contains("gif")) return "gif";
            if (contentType.contains("webp")) return "webp";
        }

        // Fallback to URL extension
        if (url != null) {
            int lastDot = url.lastIndexOf('.');
            if (lastDot > 0 && lastDot < url.length() - 1) {
                String ext = url.substring(lastDot + 1).toLowerCase();
                if (ext.equals("png") || ext.equals("jpg") || ext.equals("jpeg") || ext.equals("gif") || ext.equals("webp")) {
                    return ext;
                }
            }
        }

        return "png"; // Default
    }
}
