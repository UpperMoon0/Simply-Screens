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
        imageList = buf.readList(friendlyByteBuf -> new ImageMetadata(
                friendlyByteBuf.readUtf(),
                friendlyByteBuf.readUtf(),
                friendlyByteBuf.readUtf(),
                friendlyByteBuf.readUtf()
        ));
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeCollection(imageList, (friendlyByteBuf, imageMetadata) -> {
            friendlyByteBuf.writeUtf(imageMetadata.getName());
            friendlyByteBuf.writeUtf(imageMetadata.getId());
            friendlyByteBuf.writeUtf(imageMetadata.getExtension());
            friendlyByteBuf.writeUtf(imageMetadata.getSource());
        });
    }

    @Override
    public void handle(Supplier<NetworkManager.PacketContext> context) {
        context.get().queue(() -> {
            if (Minecraft.getInstance().screen instanceof ImageLoadScreen imageLoadScreen) {
                imageLoadScreen.getImageListWidget().updateList(imageList);
            }
        });
    }
}