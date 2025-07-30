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
    private final String internetUrl;
    private final String localId;
    private final String localExtension;
    private final BlockPos anchorPos;
    private final int screenWidth;
    private final int screenHeight;
    private final boolean maintainAspectRatio;

    public UpdateScreenS2CPacket(BlockPos pos, DisplayMode displayMode, String internetUrl, String localId, String localExtension, BlockPos anchorPos, int screenWidth, int screenHeight, boolean maintainAspectRatio) {
        this.pos = pos;
        this.displayMode = displayMode;
        this.internetUrl = internetUrl;
        this.localId = localId;
        this.localExtension = localExtension;
        this.anchorPos = anchorPos;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.maintainAspectRatio = maintainAspectRatio;
    }

    public UpdateScreenS2CPacket(FriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        displayMode = buf.readEnum(DisplayMode.class);
        internetUrl = buf.readUtf();
        localId = buf.readUtf();
        localExtension = buf.readUtf();
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
        buf.writeUtf(internetUrl);
        buf.writeUtf(localId);
        buf.writeUtf(localExtension);
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
                    screenBlockEntity.setInternetUrl(internetUrl);
                    screenBlockEntity.setLocalId(localId);
                    screenBlockEntity.setLocalExtension(localExtension);
                    screenBlockEntity.setAnchorPos(anchorPos);
                    screenBlockEntity.setScreenWidth(screenWidth);
                    screenBlockEntity.setScreenHeight(screenHeight);
                    screenBlockEntity.setMaintainAspectRatio(maintainAspectRatio);
                }
            }
        });
    }
}
