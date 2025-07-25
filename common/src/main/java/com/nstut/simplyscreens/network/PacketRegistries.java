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

        CHANNEL.register(RequestImageUploadC2SPacket.class,
                RequestImageUploadC2SPacket::write,
                RequestImageUploadC2SPacket::read,
                RequestImageUploadC2SPacket::apply
        );

        CHANNEL.register(ImageChunkC2SPacket.class,
                ImageChunkC2SPacket::write,
                ImageChunkC2SPacket::read,
                ImageChunkC2SPacket::apply
        );

        CHANNEL.register(UpdateScreenWithCachedImageS2CPacket.class,
                UpdateScreenWithCachedImageS2CPacket::write,
                UpdateScreenWithCachedImageS2CPacket::new,
                UpdateScreenWithCachedImageS2CPacket::handle
        );

        CHANNEL.register(ImageChunkS2CPacket.class,
                ImageChunkS2CPacket::write,
                ImageChunkS2CPacket::new,
                ImageChunkS2CPacket::handle
        );

        CHANNEL.register(RequestImageDownloadC2SPacket.class,
                RequestImageDownloadC2SPacket::write,
                RequestImageDownloadC2SPacket::new,
                RequestImageDownloadC2SPacket::apply
        );
    }
}
