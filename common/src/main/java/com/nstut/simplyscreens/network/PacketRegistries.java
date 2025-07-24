package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import dev.architectury.networking.NetworkChannel;
import net.minecraft.resources.ResourceLocation;

public class PacketRegistries {
    public static final NetworkChannel CHANNEL = NetworkChannel.create(
            new ResourceLocation(SimplyScreens.MOD_ID, "main")
    );

    public static void register() {
        CHANNEL.register(UpdateScreenC2SPacket.class,
                UpdateScreenC2SPacket::encode,
                UpdateScreenC2SPacket::new,
                UpdateScreenC2SPacket::handle
        );
        CHANNEL.register(UpdateScreenS2CPacket.class,
                UpdateScreenS2CPacket::encode,
                UpdateScreenS2CPacket::new,
                UpdateScreenS2CPacket::handle
        );
    }
}
