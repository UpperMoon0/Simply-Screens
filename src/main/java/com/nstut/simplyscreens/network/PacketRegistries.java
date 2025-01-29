package com.nstut.simplyscreens.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public class PacketRegistries {
    private static SimpleChannel INSTANCE;

    private static int packetId = 0;
    private static int id() {
        return packetId++;
    }

    public static void register() {
        SimpleChannel net = NetworkRegistry.ChannelBuilder
                .named(new ResourceLocation("simplyscreens", "messages"))
                .networkProtocolVersion(() -> "1.0")
                .clientAcceptedVersions(s -> true)
                .serverAcceptedVersions(s -> true)
                .simpleChannel();

        INSTANCE = net;

        net.messageBuilder(UpdateScreenC2SPacket.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(UpdateScreenC2SPacket::new)
                .encoder(UpdateScreenC2SPacket::encode)
                .consumerMainThread(UpdateScreenC2SPacket::handle)
                .add();


        // Register the server-to-client packet
        net.messageBuilder(UpdateScreenS2CPacket.class, id(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(UpdateScreenS2CPacket::new)
                .encoder(UpdateScreenS2CPacket::encode)
                .consumerMainThread(UpdateScreenS2CPacket::handle)
                .add();
    }
    public static <MSG> void sendToClients(MSG message) {
        INSTANCE.send(PacketDistributor.ALL.noArg(), message);
    }
    public static <MSG> void sendToServer(MSG message) {
        INSTANCE.sendToServer(message);
    }
}
