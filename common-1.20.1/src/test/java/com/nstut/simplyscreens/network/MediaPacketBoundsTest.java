package com.nstut.simplyscreens.network;

import com.nstut.packetweave.api.ChunkedTransfer;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MediaPacketBoundsTest {
    FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }
    @Test void rejectsOversizedUploadBeforeReassembly() {
        FriendlyByteBuf buf = buffer();
        try {
            buf.writeBlockPos(BlockPos.ZERO);
            buf.writeUUID(UUID.randomUUID());
            buf.writeVarInt(0); buf.writeVarInt(1);
            buf.writeByteArray(new byte[ChunkedTransfer.CHUNK_BYTES + 1]);
            assertThrows(DecoderException.class, () -> UploadImageChunkC2SPacket.read(buf));
        } finally { buf.release(); }
    }
    @Test void rejectsOversizedDownloadBeforeReassembly() {
        FriendlyByteBuf buf = buffer();
        try {
            buf.writeUUID(UUID.randomUUID());
            buf.writeVarInt(0); buf.writeVarInt(1);
            buf.writeByteArray(new byte[ChunkedTransfer.CHUNK_BYTES + 1]);
            assertThrows(DecoderException.class, () -> new ImageDownloadChunkS2CPacket(buf));
        } finally { buf.release(); }
    }
}
