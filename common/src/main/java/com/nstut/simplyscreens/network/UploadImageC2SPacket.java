package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.helpers.ImageMetadata;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import dev.architectury.networking.NetworkManager;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Supplier;

public class UploadImageC2SPacket {
    private final BlockPos blockPos;
    private final String fileName;
    private final byte[] data;

    public UploadImageC2SPacket(BlockPos blockPos, String fileName, byte[] data) {
        this.blockPos = blockPos;
        this.fileName = fileName;
        this.data = data;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(blockPos);
        buf.writeUtf(fileName);
        buf.writeByteArray(data);
    }

    public static UploadImageC2SPacket read(FriendlyByteBuf buf) {
        return new UploadImageC2SPacket(
                buf.readBlockPos(),
                buf.readUtf(),
                buf.readByteArray()
        );
    }

    public static void apply(UploadImageC2SPacket msg, Supplier<NetworkManager.PacketContext> context) {
        ServerPlayer player = (ServerPlayer) context.get().getPlayer();
        context.get().queue(() -> {
            UUID imageId = ServerImageManager.saveImage(player.getServer(), msg.fileName, msg.data);
            if (imageId != null) {
                player.getServer().execute(() -> {
                    if (player.level().getBlockEntity(msg.blockPos) instanceof com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity screen) {
                        screen.setImageId(imageId);
                    }
                });

                List<ImageMetadata> images = ServerImageManager.getImageList(player.getServer());
                PacketRegistries.CHANNEL.sendToPlayer(player, new UpdateImageListS2CPacket(images));
            }
        });
    }
}