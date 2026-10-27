package com.nstut.simplyscreens.network;
import dev.architectury.networking.NetworkManager;
import com.nstut.packetweave.api.OutboundTransfers;
import java.util.UUID;
import com.nstut.simplyscreens.SimplyScreens;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
public final class ImageTransferReceiptS2CPacket implements CustomPacketPayload {
    public static final Type<ImageTransferReceiptS2CPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimplyScreens.MOD_ID, "image_receipt_s2c"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ImageTransferReceiptS2CPacket> CODEC = StreamCodec.ofMember(ImageTransferReceiptS2CPacket::write, ImageTransferReceiptS2CPacket::new);
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
    private final UUID id;
    private final long bytes;
    public ImageTransferReceiptS2CPacket(UUID id, long bytes) { this.id=id; this.bytes=bytes; }
    public ImageTransferReceiptS2CPacket(RegistryFriendlyByteBuf buf) { id=buf.readUUID(); bytes=buf.readLong(); }
    public void write(RegistryFriendlyByteBuf buf) { buf.writeUUID(id); buf.writeLong(bytes); }
    public static void handle(ImageTransferReceiptS2CPacket packet, NetworkManager.PacketContext context) {
        context.queue(() -> ClientHandler.apply(packet));
    }
    private static final class ClientHandler {
        static void apply(ImageTransferReceiptS2CPacket packet) {
            Object peer=net.minecraft.client.Minecraft.getInstance().getConnection();
            if (peer != null) OutboundTransfers.shared().acknowledge(peer, packet.id, packet.bytes);
        }
    }
}
