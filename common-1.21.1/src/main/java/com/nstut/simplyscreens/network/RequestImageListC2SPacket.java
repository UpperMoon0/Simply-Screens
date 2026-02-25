package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public class RequestImageListC2SPacket implements CustomPacketPayload {
    public static final Type<RequestImageListC2SPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SimplyScreens.MOD_ID, "request_image_list_c2s"));
    
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestImageListC2SPacket> CODEC = 
            StreamCodec.ofMember(RequestImageListC2SPacket::write, RequestImageListC2SPacket::new);

    public RequestImageListC2SPacket() {
    }

    public RequestImageListC2SPacket(RegistryFriendlyByteBuf buf) {
    }

    public void write(RegistryFriendlyByteBuf buf) {
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RequestImageListC2SPacket packet, NetworkManager.PacketContext context) {
        ServerPlayer player = (ServerPlayer) context.getPlayer();
        context.queue(() -> {
            var imageList = ServerImageManager.getImageList(player.getServer());
            PacketRegistries.sendToPlayer(player, new UpdateImageListS2CPacket(imageList));
        });
    }
}