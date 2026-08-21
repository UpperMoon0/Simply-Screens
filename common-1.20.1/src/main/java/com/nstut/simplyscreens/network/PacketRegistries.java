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


        CHANNEL.register(RequestImageDownloadC2SPacket.class,
                RequestImageDownloadC2SPacket::write,
                RequestImageDownloadC2SPacket::new,
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


        CHANNEL.register(UploadImageChunkC2SPacket.class,
                UploadImageChunkC2SPacket::write,
                UploadImageChunkC2SPacket::read,
                UploadImageChunkC2SPacket::apply
        );

        CHANNEL.register(ImageDownloadChunkS2CPacket.class,
                ImageDownloadChunkS2CPacket::write,
                ImageDownloadChunkS2CPacket::new,
                (packet, context) -> packet.handle(context)
        );

        CHANNEL.register(UpdateScreenIdC2SPacket.class,
                UpdateScreenIdC2SPacket::write,
                UpdateScreenIdC2SPacket::new,
                (packet, context) -> packet.handle(context)
        );

        CHANNEL.register(DownloadImageFromUrlC2SPacket.class,
                DownloadImageFromUrlC2SPacket::write,
                DownloadImageFromUrlC2SPacket::new,
                (packet, context) -> packet.handle(context)
        );

        CHANNEL.register(RemoveImageC2SPacket.class,
                RemoveImageC2SPacket::write,
                RemoveImageC2SPacket::read,
                RemoveImageC2SPacket::apply
        );

        CHANNEL.register(InvalidateImageS2CPacket.class,
                InvalidateImageS2CPacket::write,
                InvalidateImageS2CPacket::new,
                (packet, context) -> packet.handle(context)
        );

        CHANNEL.register(ServerConfigSyncS2CPacket.class,
                ServerConfigSyncS2CPacket::write,
                ServerConfigSyncS2CPacket::new,
                (packet, context) -> packet.handle(context)
        );
    }
}
