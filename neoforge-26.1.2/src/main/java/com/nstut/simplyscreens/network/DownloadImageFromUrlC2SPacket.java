package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.Config;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import dev.architectury.networking.NetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

public class DownloadImageFromUrlC2SPacket implements CustomPacketPayload {
    public static final Type<DownloadImageFromUrlC2SPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SimplyScreens.MOD_ID, "download_image_from_url_c2s"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DownloadImageFromUrlC2SPacket> CODEC =
            StreamCodec.ofMember(DownloadImageFromUrlC2SPacket::write, DownloadImageFromUrlC2SPacket::new);

    private final BlockPos blockPos;
    private final String url;
    private final String fileName;

    public DownloadImageFromUrlC2SPacket(BlockPos blockPos, String url, String fileName) {
        this.blockPos = blockPos;
        this.url = url;
        this.fileName = fileName;
    }

    public DownloadImageFromUrlC2SPacket(RegistryFriendlyByteBuf buf) {
        this.blockPos = buf.readBlockPos();
        this.url = buf.readUtf();
        this.fileName = buf.readUtf();
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(blockPos);
        buf.writeUtf(url);
        buf.writeUtf(fileName);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public BlockPos getBlockPos() {
        return blockPos;
    }

    public String getUrl() {
        return url;
    }

    public String getFileName() {
        return fileName;
    }

    public static void handle(DownloadImageFromUrlC2SPacket packet, NetworkManager.PacketContext context) {
        context.queue(() -> {
            ServerPlayer player = (ServerPlayer) context.getPlayer();
            if (player == null) {
                return;
            }

            // Validate URL
            String url = packet.getUrl();
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
                    HttpClient client = HttpClient.newBuilder()
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build();
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(java.time.Duration.ofSeconds(30))
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .header("Accept", "image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                            .header("Accept-Language", "en-US,en;q=0.9")
                            .header("Referer", "https://www.google.com/")
                            .GET()
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
                        String fileName = packet.getFileName() != null ? packet.getFileName() : "url_image";
                        var imageId = ServerImageManager.saveImage(player.level().getServer(), fileName, imageData, null, player.getUUID().toString());
                        if (imageId != null) {
                            SimplyScreens.LOGGER.info("Successfully downloaded image from URL: {} (ID: {})", url, imageId);

                            // Set the image on the screen block
                            player.level().getServer().execute(() -> {
                                ServerLevel level = player.level();
                                var blockEntity = level.getBlockEntity(packet.getBlockPos());
                                if (blockEntity instanceof com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity screen) {
                                    var anchor = screen.getAnchorEntity();
                                    if (anchor != null) {
                                        anchor.setImageId(imageId);
                                    }
                                }

                                // Send updated image list to player
                                var images = ServerImageManager.getImageListForPlayer(player.level().getServer(), player.getUUID().toString());
                                PacketRegistries.sendToPlayer(player, new UpdateImageListS2CPacket(images));
                            });
                        } else {
                            player.level().getServer().execute(() -> {
                                player.sendSystemMessage(Component.literal("§c[Simply Screens] Failed to load image from URL: the content is not a valid image or is in an unsupported format."));
                            });
                        }
                    } else if (response.statusCode() == 403) {
                        SimplyScreens.LOGGER.warn("Access forbidden (403) for URL: {} - The server is blocking this request. Try a different image host like Imgur or direct image links.", url);
                    } else if (response.statusCode() == 404) {
                        SimplyScreens.LOGGER.warn("Image not found (404) for URL: {} - The image may have been removed or the URL is incorrect.", url);
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



