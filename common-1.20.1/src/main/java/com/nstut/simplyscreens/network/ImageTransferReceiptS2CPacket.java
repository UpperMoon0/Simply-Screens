package com.nstut.simplyscreens.network;
import dev.architectury.networking.NetworkManager;
import com.nstut.packetweave.api.OutboundTransfers;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
public final class ImageTransferReceiptS2CPacket implements IPacket {
    private final UUID id;
    private final long bytes;
    public ImageTransferReceiptS2CPacket(UUID id, long bytes) { this.id=id; this.bytes=bytes; }
    public ImageTransferReceiptS2CPacket(FriendlyByteBuf buf) { id=buf.readUUID(); bytes=buf.readLong(); }
    public void write(FriendlyByteBuf buf) { buf.writeUUID(id); buf.writeLong(bytes); }
    public void handle(java.util.function.Supplier<NetworkManager.PacketContext> context) {
        context.get().queue(() -> ClientHandler.apply(this));
    }
    private static final class ClientHandler {
        static void apply(ImageTransferReceiptS2CPacket packet) {
            Object peer=net.minecraft.client.Minecraft.getInstance().getConnection();
            if (peer != null) OutboundTransfers.shared().acknowledge(peer, packet.id, packet.bytes);
        }
    }
}
