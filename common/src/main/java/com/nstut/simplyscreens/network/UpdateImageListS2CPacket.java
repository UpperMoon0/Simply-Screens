package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.helpers.ImageMetadata;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public class UpdateImageListS2CPacket implements CustomPacketPayload {
    public static final Type<UpdateImageListS2CPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SimplyScreens.MOD_ID, "update_image_list_s2c"));
    
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateImageListS2CPacket> CODEC = 
            StreamCodec.ofMember(UpdateImageListS2CPacket::write, UpdateImageListS2CPacket::new);

    private final List<ImageMetadata> imageList;

    public UpdateImageListS2CPacket(List<ImageMetadata> imageList) {
        this.imageList = imageList;
    }

    public UpdateImageListS2CPacket(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        this.imageList = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            this.imageList.add(new ImageMetadata(buf));
        }
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(imageList.size());
        for (ImageMetadata metadata : imageList) {
            metadata.write(buf);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public List<ImageMetadata> getImageList() {
        return imageList;
    }

    public static void handle(UpdateImageListS2CPacket packet, NetworkManager.PacketContext context) {
        context.queue(() -> {
            // This will be handled on the client side
        });
    }
}