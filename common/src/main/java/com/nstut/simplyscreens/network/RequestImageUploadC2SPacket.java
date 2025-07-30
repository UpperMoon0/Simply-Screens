package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import dev.architectury.networking.NetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Supplier;

public class RequestImageUploadC2SPacket {
    public static final ResourceLocation ID = new ResourceLocation(SimplyScreens.MOD_ID, "request_image_upload");

    private final String source;
    private final byte[] data;
    private final String originalName;
    private final BlockPos blockPos;
    private final boolean maintainAspectRatio;

    public RequestImageUploadC2SPacket(String source, byte[] data, String originalName, BlockPos blockPos, boolean maintainAspectRatio) {
        this.source = source;
        this.data = data;
        this.originalName = originalName;
        this.blockPos = blockPos;
        this.maintainAspectRatio = maintainAspectRatio;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(source);
        buf.writeByteArray(data);
        buf.writeUtf(originalName);
        buf.writeBlockPos(blockPos);
        buf.writeBoolean(maintainAspectRatio);
    }

    public static RequestImageUploadC2SPacket read(FriendlyByteBuf buf) {
        return new RequestImageUploadC2SPacket(
                buf.readUtf(),
                buf.readByteArray(),
                buf.readUtf(),
                buf.readBlockPos(),
                buf.readBoolean()
        );
    }

    public static void apply(RequestImageUploadC2SPacket msg, Supplier<NetworkManager.PacketContext> context) {
        ServerPlayer player = (ServerPlayer) context.get().getPlayer();
        context.get().queue(() -> {
            String imageId = ServerImageManager.saveImage(player.getServer(), msg.source, msg.data, msg.originalName);
            if (imageId != null) {
                player.getServer().execute(() -> {
                    if (player.level().getBlockEntity(msg.blockPos) instanceof com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity screen) {
                        String extension = msg.originalName.substring(msg.originalName.lastIndexOf('.') + 1);
                        screen.setImage(imageId, extension, msg.maintainAspectRatio);
                        UpdateScreenWithCachedImageS2CPacket packet = new UpdateScreenWithCachedImageS2CPacket(msg.blockPos, imageId + "." + extension, msg.maintainAspectRatio);
                        for (ServerPlayer p : player.getServer().getPlayerList().getPlayers()) {
                            if (p.level().isLoaded(msg.blockPos)) {
                                PacketRegistries.CHANNEL.sendToPlayer(p, packet);
                            }
                        }
                    }
                });
            }
        });
    }
}