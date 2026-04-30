package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import dev.architectury.networking.NetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public class UpdateScreenS2CPacket implements CustomPacketPayload {
    public static final Type<UpdateScreenS2CPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SimplyScreens.MOD_ID, "update_screen_s2c"));
    
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateScreenS2CPacket> CODEC = 
            StreamCodec.ofMember(UpdateScreenS2CPacket::write, UpdateScreenS2CPacket::new);

    private final BlockPos pos;
    private final UUID imageId;
    private final boolean maintainAspectRatio;
    private final String screenId;

    public UpdateScreenS2CPacket(BlockPos pos, UUID imageId, boolean maintainAspectRatio, String screenId) {
        this.pos = pos;
        this.imageId = imageId;
        this.maintainAspectRatio = maintainAspectRatio;
        this.screenId = screenId;
    }

    public UpdateScreenS2CPacket(RegistryFriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        if (buf.readBoolean()) {
            imageId = buf.readUUID();
        } else {
            imageId = null;
        }
        maintainAspectRatio = buf.readBoolean();
        screenId = buf.readBoolean() ? buf.readUtf() : "";
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeBoolean(imageId != null);
        if (imageId != null) {
            buf.writeUUID(imageId);
        }
        buf.writeBoolean(maintainAspectRatio);
        buf.writeBoolean(screenId != null && !screenId.isEmpty());
        if (screenId != null && !screenId.isEmpty()) {
            buf.writeUtf(screenId);
        }
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

    public boolean isMaintainAspectRatio() {
        return maintainAspectRatio;
    }

    public String getScreenId() {
        return screenId;
    }

    public static void handle(UpdateScreenS2CPacket packet, NetworkManager.PacketContext context) {
        context.queue(() -> ClientPacketHandler.handleUpdateScreen(packet.pos, packet.imageId, packet.maintainAspectRatio, packet.screenId));
    }
}