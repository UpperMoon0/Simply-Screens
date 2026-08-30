package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.client.screens.ImageLoadScreen;
import com.nstut.simplyscreens.helpers.ImageMetadata;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.function.Supplier;

public class UpdateImageListS2CPacket implements IPacket {
    public static final ResourceLocation ID = new ResourceLocation(SimplyScreens.MOD_ID, "update_image_list");

    private final List<ImageMetadata> imageList;

    public UpdateImageListS2CPacket(List<ImageMetadata> imageList) {
        this.imageList = imageList;
    }

    public UpdateImageListS2CPacket(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > 1024) throw new IllegalArgumentException("Invalid image-list size: " + size);
        java.util.ArrayList<ImageMetadata> images = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) images.add(new ImageMetadata(
                buf.readUtf(com.nstut.simplyscreens.ImageNameSanitizer.MAX_LENGTH), buf.readUtf(36), buf.readUtf(8)));
        imageList = images;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        if (imageList.size() > 1024) {
            SimplyScreens.LOGGER.warn("Image list truncated from {} to the 1024-entry protocol maximum; larger libraries need pagination", imageList.size());
        }
        int size = Math.min(imageList.size(), 1024);
        buf.writeVarInt(size);
        for (ImageMetadata imageMetadata : imageList.subList(0, size)) {
            buf.writeUtf(imageMetadata.getName(), com.nstut.simplyscreens.ImageNameSanitizer.MAX_LENGTH);
            buf.writeUtf(imageMetadata.getId(), 36);
            buf.writeUtf(imageMetadata.getExtension(), 8);
        }
    }

    @Override
    public void handle(Supplier<NetworkManager.PacketContext> context) {
        context.get().queue(() -> {
            com.nstut.simplyscreens.helpers.ClientImageManager.updateImageCache(imageList);
            if (Minecraft.getInstance().screen instanceof ImageLoadScreen imageLoadScreen) {
                imageLoadScreen.updateImageList(imageList);
            }
        });
    }
}
