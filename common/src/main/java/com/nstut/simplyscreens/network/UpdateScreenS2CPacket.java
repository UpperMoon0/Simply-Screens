package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import dev.architectury.networking.NetworkManager;
import java.util.function.Supplier;

public class UpdateScreenS2CPacket {
    private final BlockPos pos;
    private final String imagePath;
    private final BlockPos anchorPos;
    private final int screenWidth;
    private final int screenHeight;
    private final boolean maintainAspectRatio;

    public UpdateScreenS2CPacket(BlockPos pos, String imagePath, BlockPos anchorPos, int screenWidth, int screenHeight, boolean maintainAspectRatio) {
        this.pos = pos;
        this.imagePath = imagePath;
        this.anchorPos = anchorPos;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.maintainAspectRatio = maintainAspectRatio;
    }

    public UpdateScreenS2CPacket(FriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        imagePath = buf.readUtf();
        if (buf.readBoolean()) {
            anchorPos = buf.readBlockPos();
        } else {
            anchorPos = null;
        }
        screenWidth = buf.readInt();
        screenHeight = buf.readInt();
        maintainAspectRatio = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(imagePath);
        buf.writeBoolean(anchorPos != null);
        if (anchorPos != null) {
            buf.writeBlockPos(anchorPos);
        }
        buf.writeInt(screenWidth);
        buf.writeInt(screenHeight);
        buf.writeBoolean(maintainAspectRatio);
    }

    public void handle(Supplier<NetworkManager.PacketContext> context) {
        context.get().queue(() -> {
            Level level = Minecraft.getInstance().level;
            if (level != null) {
                BlockEntity blockEntity = level.getBlockEntity(pos);
                if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                    screenBlockEntity.setImagePath(imagePath);
                    screenBlockEntity.setAnchorPos(anchorPos);
                    screenBlockEntity.setScreenWidth(screenWidth);
                    screenBlockEntity.setScreenHeight(screenHeight);
                    screenBlockEntity.setMaintainAspectRatio(maintainAspectRatio);
                }
            }
        });
    }
}
