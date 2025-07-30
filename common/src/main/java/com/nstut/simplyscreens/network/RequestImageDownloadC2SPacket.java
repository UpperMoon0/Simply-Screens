package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.helpers.ServerImageCache;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Supplier;

public class RequestImageDownloadC2SPacket {
    public static final ResourceLocation ID = new ResourceLocation(SimplyScreens.MOD_ID, "request_image_download");

    private final String imageId;

    public RequestImageDownloadC2SPacket(String imageId) {
        this.imageId = imageId;
    }

    public RequestImageDownloadC2SPacket(FriendlyByteBuf buf) {
        this.imageId = buf.readUtf();
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(imageId);
    }

    public static void apply(RequestImageDownloadC2SPacket msg, Supplier<NetworkManager.PacketContext> context) {
        ServerPlayer player = (ServerPlayer) context.get().getPlayer();
        context.get().queue(() -> ServerImageCache.handleRequestImageDownload(msg, player));
    }

    public String getImageId() {
        return imageId;
    }
}