package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import dev.architectury.networking.NetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public class InvalidateImageS2CPacket implements CustomPacketPayload {
    public static final Type<InvalidateImageS2CPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SimplyScreens.MOD_ID, "invalidate_image_s2c"));

    public static final StreamCodec<RegistryFriendlyByteBuf, InvalidateImageS2CPacket> CODEC =
            StreamCodec.ofMember(InvalidateImageS2CPacket::write, InvalidateImageS2CPacket::new);

    private final UUID imageId;

    public InvalidateImageS2CPacket(UUID imageId) {
        this.imageId = imageId;
    }

    public InvalidateImageS2CPacket(RegistryFriendlyByteBuf buf) {
        this.imageId = buf.readBoolean() ? buf.readUUID() : null;
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeBoolean(imageId != null);
        if (imageId != null) {
            buf.writeUUID(imageId);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public UUID getImageId() {
        return imageId;
    }

    public static void handle(InvalidateImageS2CPacket packet, NetworkManager.PacketContext context) {
        context.queue(() -> ClientPacketHandler.handleInvalidateImage(packet.imageId));
    }
}
