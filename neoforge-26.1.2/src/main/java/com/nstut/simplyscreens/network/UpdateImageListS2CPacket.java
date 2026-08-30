package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.helpers.ImageMetadata;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

public class UpdateImageListS2CPacket implements CustomPacketPayload {
    public static final Type<UpdateImageListS2CPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SimplyScreens.MOD_ID, "update_image_list_s2c"));
    
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateImageListS2CPacket> CODEC = 
            StreamCodec.ofMember(UpdateImageListS2CPacket::write, UpdateImageListS2CPacket::new);

    private final List<ImageMetadata> imageList;

    public UpdateImageListS2CPacket(List<ImageMetadata> imageList) {
        this.imageList = imageList;
    }

    public UpdateImageListS2CPacket(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > 1024) throw new IllegalArgumentException("Invalid image-list size: " + size);
        this.imageList = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            this.imageList.add(new ImageMetadata(buf));
        }
    }

    public void write(RegistryFriendlyByteBuf buf) {
        if (imageList.size() > 1024) {
            SimplyScreens.LOGGER.warn("Image list truncated from {} to the 1024-entry protocol maximum; larger libraries need pagination", imageList.size());
        }
        int size = Math.min(imageList.size(), 1024);
        buf.writeVarInt(size);
        for (ImageMetadata metadata : imageList.subList(0, size)) {
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
            com.nstut.simplyscreens.helpers.ClientImageManager.updateImageCache(packet.getImageList());
            if (Minecraft.getInstance().screen instanceof com.nstut.simplyscreens.client.screens.ImageLoadScreen imageLoadScreen) {
                imageLoadScreen.updateImageList(packet.getImageList());
            }
        });
    }
}

