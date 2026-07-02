package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import dev.architectury.networking.NetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

public class UpdateScreenS2CPacket implements CustomPacketPayload {
    public static final Type<UpdateScreenS2CPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SimplyScreens.MOD_ID, "update_screen_s2c"));
    
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateScreenS2CPacket> CODEC = 
            StreamCodec.ofMember(UpdateScreenS2CPacket::write, UpdateScreenS2CPacket::new);

    private final BlockPos pos;
    private final BlockPos anchorPos;
    private final UUID imageId;
    private final boolean maintainAspectRatio;
    private final String screenId;
    private final int screenWidth;
    private final int screenHeight;

    public UpdateScreenS2CPacket(BlockPos pos, BlockPos anchorPos, UUID imageId, boolean maintainAspectRatio, String screenId, int screenWidth, int screenHeight) {
        this.pos = pos;
        this.anchorPos = anchorPos;
        this.imageId = imageId;
        this.maintainAspectRatio = maintainAspectRatio;
        this.screenId = screenId;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
    }

    public UpdateScreenS2CPacket(RegistryFriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        anchorPos = buf.readBlockPos();
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

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeBlockPos(anchorPos);
        buf.writeBoolean(imageId != null);
        if (imageId != null) {
            buf.writeUUID(imageId);
        }
        buf.writeBoolean(maintainAspectRatio);
        buf.writeBoolean(screenId != null && !screenId.isEmpty());
        if (screenId != null && !screenId.isEmpty()) {
            buf.writeUtf(screenId);
        }
        buf.writeVarInt(screenWidth);
        buf.writeVarInt(screenHeight);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public BlockPos getPos() {
        return pos;
    }

    public UUID getImageId() {
        return imageId;
    }

    public BlockPos getAnchorPos() {
        return anchorPos;
    }

    public boolean isMaintainAspectRatio() {
        return maintainAspectRatio;
    }

    public String getScreenId() {
        return screenId;
    }

    public int getScreenWidth() {
        return screenWidth;
    }

    public int getScreenHeight() {
        return screenHeight;
    }

    public static void handle(UpdateScreenS2CPacket packet, NetworkManager.PacketContext context) {
        context.queue(() -> ClientPacketHandler.handleUpdateScreen(packet.pos, packet.anchorPos, packet.imageId, packet.maintainAspectRatio, packet.screenId, packet.screenWidth, packet.screenHeight));
    }
}

