package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.helpers.ClientImageManager;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;
import java.util.function.Supplier;

public class ImageDownloadS2CPacket implements IPacket {
    private final UUID imageId;
    private final byte[] imageData;

    public ImageDownloadS2CPacket(UUID imageId, byte[] imageData) {
        this.imageId = imageId;
        this.imageData = imageData;
    }

    public ImageDownloadS2CPacket(FriendlyByteBuf buf) {
        this.imageId = buf.readUUID();
        this.imageData = buf.readByteArray();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(imageId);
        buf.writeByteArray(imageData);
    }

    @Override
    public void handle(Supplier<NetworkManager.PacketContext> context) {
        context.get().queue(() -> ClientImageManager.saveImageToCache(imageId, imageData));
    }
}