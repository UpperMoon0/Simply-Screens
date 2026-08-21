package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.Config;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import com.nstut.simplyscreens.helpers.UrlSecurity;
import com.nstut.simplyscreens.helpers.ScreenPacketSecurity;
import dev.architectury.networking.NetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import com.nstut.simplyscreens.helpers.ChunkedFileTransfer;

public class DownloadImageFromUrlC2SPacket implements CustomPacketPayload {
    private static final ExecutorService URL_EXECUTOR = ChunkedFileTransfer.newDaemonBoundedThreadPool(2, 16, "Simply Screens URL Import");
    private static final Set<UUID> ACTIVE_PLAYERS = ConcurrentHashMap.newKeySet();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    public static final Type<DownloadImageFromUrlC2SPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SimplyScreens.MOD_ID, "download_image_from_url_c2s"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DownloadImageFromUrlC2SPacket> CODEC =
            StreamCodec.ofMember(DownloadImageFromUrlC2SPacket::write, DownloadImageFromUrlC2SPacket::new);

    private final BlockPos blockPos;
    private final String url;
    private final String fileName;

    public DownloadImageFromUrlC2SPacket(BlockPos blockPos, String url, String fileName) {
        this.blockPos = blockPos;
        this.url = url;
        this.fileName = com.nstut.simplyscreens.ImageNameSanitizer.sanitize(fileName);
    }

    public DownloadImageFromUrlC2SPacket(RegistryFriendlyByteBuf buf) {
        this.blockPos = buf.readBlockPos();
        this.url = buf.readUtf(2048);
        this.fileName = buf.readUtf(com.nstut.simplyscreens.ImageNameSanitizer.MAX_LENGTH);
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(blockPos);
        buf.writeUtf(url, 2048);
        buf.writeUtf(fileName, com.nstut.simplyscreens.ImageNameSanitizer.MAX_LENGTH);
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
            if (Config.DISABLE_URL_DOWNLOAD || !ScreenPacketSecurity.canModify(player, packet.getBlockPos())) return;

            // Validate URL
            String url = packet.getUrl();
            if (url == null || url.isEmpty()) {
                SimplyScreens.LOGGER.warn("Received empty URL from player {}", player.getName().getString());
                return;
            }

            // Basic URL validation
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                SimplyScreens.LOGGER.warn("Invalid URL protocol from player {}: {}", player.getName().getString(), UrlSecurity.sanitizeForLogging(url));
                return;
            }

            ServerLevel intendedLevel = player.serverLevel();
            UUID playerUUID = player.getUUID();
            net.minecraft.server.MinecraftServer server = player.getServer();
            if (server == null) return;
            if (!ACTIVE_PLAYERS.add(playerUUID)) return;
            try {
                URL_EXECUTOR.execute(() -> {
                    String sanitizedUrl = UrlSecurity.sanitizeForLogging(url);
                    try {
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(UrlSecurity.requirePublicHttpUrl(url))
                                .timeout(java.time.Duration.ofSeconds(30))
                                .header("User-Agent", "Simply-Screens/0.8.4")
                                .header("Accept", "image/png,image/jpeg,image/gif,image/*;q=0.8")
                                .header("Accept-Language", "en-US,en;q=0.9")
                                .GET()
                                .build();

                        HttpResponse<InputStream> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
                        if (response.statusCode() != 200) response.body().close();

                        if (response.statusCode() == 200) {
                            byte[] imageData;
                            try (InputStream body = response.body()) {
                                imageData = body.readNBytes(Config.MAX_URL_DOWNLOAD_SIZE + 1);
                            }
                            if (imageData == null || imageData.length == 0) {
                                SimplyScreens.LOGGER.warn("Empty image data received from URL: {}", sanitizedUrl);
                                return;
                            }

                            // Check file size limit
                            if (imageData.length > Config.MAX_URL_DOWNLOAD_SIZE) {
                                SimplyScreens.LOGGER.warn("Image from URL {} exceeds maximum size: {} bytes (max: {})", sanitizedUrl, imageData.length, Config.MAX_URL_DOWNLOAD_SIZE);
                                return;
                            }

                            String fileName = packet.getFileName() != null ? packet.getFileName() : "url_image";
                            UUID imageId = ServerImageManager.saveImage(server, fileName, imageData, null, playerUUID.toString());
                            if (imageId != null) {
                                SimplyScreens.LOGGER.info("Successfully downloaded image from URL: {} (ID: {})", sanitizedUrl, imageId);

                                server.execute(() -> {
                                    ServerPlayer p = server.getPlayerList().getPlayer(playerUUID);
                                    if (p == null || p.serverLevel() != intendedLevel || !ScreenPacketSecurity.canModify(p, packet.getBlockPos())) return;
                                    var blockEntity = intendedLevel.getBlockEntity(packet.getBlockPos());
                                    if (blockEntity instanceof com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity screen) {
                                        var anchor = screen.getAnchorEntity();
                                        if (anchor != null) {
                                            anchor.setImageId(imageId);
                                        }
                                    }

                                    // Send updated image list to player
                                    var images = ServerImageManager.getImageListForPlayer(server, playerUUID.toString());
                                    PacketRegistries.sendToPlayer(p, new UpdateImageListS2CPacket(images));
                                });
                            } else {
                                server.execute(() -> {
                                    ServerPlayer p = server.getPlayerList().getPlayer(playerUUID);
                                    if (p != null) {
                                        p.sendSystemMessage(Component.literal("§c[Simply Screens] Failed to load image from URL: the content is not a valid image or is in an unsupported format."));
                                    }
                                });
                            }
                        } else if (response.statusCode() == 403) {
                            SimplyScreens.LOGGER.warn("Access forbidden (403) for URL: {} - The server is blocking this request. Try a different image host like Imgur or direct image links.", sanitizedUrl);
                        } else if (response.statusCode() == 404) {
                            SimplyScreens.LOGGER.warn("Image not found (404) for URL: {} - The image may have been removed or the URL is incorrect.", sanitizedUrl);
                        } else {
                            SimplyScreens.LOGGER.warn("Failed to download image from URL: {} - HTTP {}", sanitizedUrl, response.statusCode());
                        }
                    } catch (Exception e) {
                        SimplyScreens.LOGGER.error("Error downloading image from URL: {}", sanitizedUrl, e);
                    } finally {
                        ACTIVE_PLAYERS.remove(playerUUID);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                ACTIVE_PLAYERS.remove(playerUUID);
                player.sendSystemMessage(Component.literal("§c[Simply Screens] URL import queue is full, please try again later."));
            }
        });
    }

}
