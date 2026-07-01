package com.nstut.simplyscreens.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import dev.architectury.networking.NetworkManager;
import java.util.UUID;
import java.util.function.Supplier;

public class UpdateScreenS2CPacket {
    public final BlockPos pos;
    public final UUID imageId;
    public final boolean maintainAspectRatio;
    public final String screenId;
    public final int screenWidth;
    public final int screenHeight;

    public UpdateScreenS2CPacket(BlockPos pos, UUID imageId, boolean maintainAspectRatio, String screenId, int screenWidth, int screenHeight) {
        this.pos = pos;
        this.imageId = imageId;
        this.maintainAspectRatio = maintainAspectRatio;
        this.screenId = screenId != null ? screenId : "";
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
    }

    public UpdateScreenS2CPacket(FriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        if (buf.readBoolean()) {
            imageId = buf.readUUID();
        } else {
            imageId = null;
        }
        maintainAspectRatio = buf.readBoolean();
        screenId = buf.readBoolean() ? buf.readUtf() : "";
        screenWidth = buf.readVarInt();
        screenHeight = buf.readVarInt();
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeBoolean(imageId != null);
        if (imageId != null) {
            buf.writeUUID(imageId);
        }
        buf.writeBoolean(maintainAspectRatio);
        buf.writeBoolean(!screenId.isEmpty());
        if (!screenId.isEmpty()) buf.writeUtf(screenId);
        buf.writeVarInt(screenWidth);
        buf.writeVarInt(screenHeight);
    }

    public void handle(Supplier<NetworkManager.PacketContext> context) {
        context.get().queue(() -> ClientPacketHandler.handleUpdateScreen(pos, imageId, maintainAspectRatio, screenId, screenWidth, screenHeight));
    }
}
