package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.DisplayMode;
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
    private final DisplayMode displayMode;
    private final String imageUrl;
    private final String internetUrl;
    private final String imageHash;
    private final BlockPos anchorPos;
    private final int screenWidth;
    private final int screenHeight;
    private final boolean maintainAspectRatio;

    public UpdateScreenS2CPacket(BlockPos pos, DisplayMode displayMode, String imageUrl, String internetUrl, String imageHash, BlockPos anchorPos, int screenWidth, int screenHeight, boolean maintainAspectRatio) {
        this.pos = pos;
        this.displayMode = displayMode;
        this.imageUrl = imageUrl;
        this.internetUrl = internetUrl;
        this.imageHash = imageHash;
        this.anchorPos = anchorPos;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.maintainAspectRatio = maintainAspectRatio;
    }

    public UpdateScreenS2CPacket(FriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        displayMode = buf.readEnum(DisplayMode.class);
        imageUrl = buf.readUtf();
        internetUrl = buf.readUtf();
        imageHash = buf.readUtf();
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
        buf.writeEnum(displayMode);
        buf.writeUtf(imageUrl);
        buf.writeUtf(internetUrl);
        buf.writeUtf(imageHash);
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
                    screenBlockEntity.setDisplayMode(displayMode);
                    screenBlockEntity.setImageUrl(imageUrl);
                    screenBlockEntity.setInternetUrl(internetUrl);
                    screenBlockEntity.setImageHash(imageHash);
                    screenBlockEntity.setAnchorPos(anchorPos);
                    screenBlockEntity.setScreenWidth(screenWidth);
                    screenBlockEntity.setScreenHeight(screenHeight);
                    screenBlockEntity.setMaintainAspectRatio(maintainAspectRatio);
                }
            }
        });
    }
}
