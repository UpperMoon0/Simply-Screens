package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import dev.architectury.networking.NetworkChannel;
import net.minecraft.resources.ResourceLocation;

public class PacketRegistries {
    public static final NetworkChannel CHANNEL = NetworkChannel.create(
            new ResourceLocation(SimplyScreens.MOD_ID, "main")
    );

    public static void register() {
        CHANNEL.register(UpdateScreenSelectedImageC2SPacket.class,
                UpdateScreenSelectedImageC2SPacket::write,
                UpdateScreenSelectedImageC2SPacket::new,
                (packet, context) -> packet.handle(context)
        );
        CHANNEL.register(UpdateScreenS2CPacket.class,
                UpdateScreenS2CPacket::write,
                UpdateScreenS2CPacket::new,
                (packet, context) -> packet.handle(context)
        );

        CHANNEL.register(UpdateScreenAspectRatioC2SPacket.class,
                UpdateScreenAspectRatioC2SPacket::write,
                UpdateScreenAspectRatioC2SPacket::new,
                (packet, context) -> packet.handle(context)
        );

        CHANNEL.register(RequestImageUploadC2SPacket.class,
                RequestImageUploadC2SPacket::write,
                RequestImageUploadC2SPacket::read,
                RequestImageUploadC2SPacket::apply
        );

        CHANNEL.register(RequestImageDownloadC2SPacket.class,
                RequestImageDownloadC2SPacket::write,
                RequestImageDownloadC2SPacket::new,
                (packet, context) -> packet.handle(context)
        );

        CHANNEL.register(ImageDownloadS2CPacket.class,
                ImageDownloadS2CPacket::write,
                ImageDownloadS2CPacket::new,
                (packet, context) -> packet.handle(context)
        );

        CHANNEL.register(RequestImageListC2SPacket.class,
                RequestImageListC2SPacket::write,
                RequestImageListC2SPacket::read,
                RequestImageListC2SPacket::apply
        );

        CHANNEL.register(UpdateImageListS2CPacket.class,
                UpdateImageListS2CPacket::write,
                UpdateImageListS2CPacket::new,
                (packet, context) -> packet.handle(context)
        );

        CHANNEL.register(DownloadImageFromUrlC2SPacket.class,
                DownloadImageFromUrlC2SPacket::write,
                DownloadImageFromUrlC2SPacket::read,
                DownloadImageFromUrlC2SPacket::apply
        );

        CHANNEL.register(UploadImageC2SPacket.class,
                UploadImageC2SPacket::write,
                UploadImageC2SPacket::read,
                UploadImageC2SPacket::apply
        );
    }
}
