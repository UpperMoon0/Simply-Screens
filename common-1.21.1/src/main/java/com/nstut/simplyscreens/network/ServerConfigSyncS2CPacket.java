package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.Config;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public class ServerConfigSyncS2CPacket implements CustomPacketPayload {
    public static final Type<ServerConfigSyncS2CPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SimplyScreens.MOD_ID, "server_config_sync_s2c"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerConfigSyncS2CPacket> CODEC =
            StreamCodec.ofMember(ServerConfigSyncS2CPacket::write, ServerConfigSyncS2CPacket::new);

    private final boolean disableUpload;
    private final boolean disableUrlDownload;
    private final int maxUploadSize;

    public ServerConfigSyncS2CPacket(boolean disableUpload, boolean disableUrlDownload, int maxUploadSize) {
        this.disableUpload = disableUpload;
        this.disableUrlDownload = disableUrlDownload;
        this.maxUploadSize = maxUploadSize;
    }

    public ServerConfigSyncS2CPacket(RegistryFriendlyByteBuf buf) {
        this.disableUpload = buf.readBoolean();
        this.disableUrlDownload = buf.readBoolean();
        this.maxUploadSize = buf.readVarInt();
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeBoolean(disableUpload);
        buf.writeBoolean(disableUrlDownload);
        buf.writeVarInt(maxUploadSize);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ServerConfigSyncS2CPacket packet, NetworkManager.PacketContext context) {
        context.queue(() -> ClientPacketHandler.handleConfigSync(packet.disableUpload, packet.disableUrlDownload, packet.maxUploadSize));
    }
}
