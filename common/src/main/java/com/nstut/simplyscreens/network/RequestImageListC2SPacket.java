package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.helpers.ImageMetadata;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Supplier;

public class RequestImageListC2SPacket {
    public static final ResourceLocation ID = new ResourceLocation(SimplyScreens.MOD_ID, "request_image_list");

    public RequestImageListC2SPacket() {
    }

    public void write(FriendlyByteBuf buf) {
    }

    public static RequestImageListC2SPacket read(FriendlyByteBuf buf) {
        return new RequestImageListC2SPacket();
    }

    public static void apply(RequestImageListC2SPacket msg, Supplier<NetworkManager.PacketContext> context) {
        ServerPlayer player = (ServerPlayer) context.get().getPlayer();
        context.get().queue(() -> {
            var imageList = ServerImageManager.getImageList(player.getServer());
            PacketRegistries.CHANNEL.sendToPlayer(player, new UpdateImageListS2CPacket(imageList));
        });
    }
}