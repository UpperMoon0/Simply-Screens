package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import dev.architectury.networking.NetworkManager;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.function.Supplier;

public class DownloadImageFromUrlC2SPacket {
    private final BlockPos blockPos;
    private final String url;
    private final boolean maintainAspectRatio;

    public DownloadImageFromUrlC2SPacket(BlockPos blockPos, String url, boolean maintainAspectRatio) {
        this.blockPos = blockPos;
        this.url = url;
        this.maintainAspectRatio = maintainAspectRatio;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(blockPos);
        buf.writeUtf(url);
        buf.writeBoolean(maintainAspectRatio);
    }

    public static DownloadImageFromUrlC2SPacket read(FriendlyByteBuf buf) {
        return new DownloadImageFromUrlC2SPacket(
                buf.readBlockPos(),
                buf.readUtf(),
                buf.readBoolean()
        );
    }

    public static void apply(DownloadImageFromUrlC2SPacket msg, Supplier<NetworkManager.PacketContext> context) {
        ServerPlayer player = (ServerPlayer) context.get().getPlayer();
        context.get().queue(() -> {
            new Thread(() -> {
                HttpURLConnection connection = null;
                try {
                    URL url = new URL(msg.url);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setInstanceFollowRedirects(true);
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
                    connection.setRequestProperty("Accept", "image/png,image/jpeg,image/gif,image/webp,image/*,*/*;q=0.8");

                    try (InputStream in = connection.getInputStream()) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buf = new byte[4096];
                        int n;
                        while (-1 != (n = in.read(buf))) {
                            out.write(buf, 0, n);
                        }
                        out.close();
                        byte[] response = out.toByteArray();

                        UUID imageId = ServerImageManager.saveImage(player.getServer(), msg.url, response);
                        if (imageId != null) {
                            player.getServer().execute(() -> {
                                if (player.level().getBlockEntity(msg.blockPos) instanceof com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity screen) {
                                    screen.setImageId(imageId);
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                    SimplyScreens.LOGGER.error("Failed to load image from URL", e);
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }).start();
        });
    }
}