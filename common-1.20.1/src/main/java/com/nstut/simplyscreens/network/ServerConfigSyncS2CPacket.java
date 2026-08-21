package com.nstut.simplyscreens.network;

import dev.architectury.networking.NetworkManager;
import net.minecraft.network.FriendlyByteBuf;
import java.util.function.Supplier;

public class ServerConfigSyncS2CPacket {
    public final boolean disableUpload;
    public final boolean disableUrlDownload;
    public final int maxUploadSize;

    public ServerConfigSyncS2CPacket(boolean disableUpload, boolean disableUrlDownload, int maxUploadSize) {
        this.disableUpload = disableUpload;
        this.disableUrlDownload = disableUrlDownload;
        this.maxUploadSize = maxUploadSize;
    }

    public ServerConfigSyncS2CPacket(FriendlyByteBuf buf) {
        this.disableUpload = buf.readBoolean();
        this.disableUrlDownload = buf.readBoolean();
        this.maxUploadSize = buf.readVarInt();
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(disableUpload);
        buf.writeBoolean(disableUrlDownload);
        buf.writeVarInt(maxUploadSize);
    }

    public void handle(Supplier<NetworkManager.PacketContext> context) {
        context.get().queue(() -> ClientPacketHandler.handleConfigSync(disableUpload, disableUrlDownload, maxUploadSize));
    }
}
