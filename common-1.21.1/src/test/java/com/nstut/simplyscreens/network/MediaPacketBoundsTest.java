package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.internal.packetweave.api.ChunkedTransfer;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MediaPacketBoundsTest {
    RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), mock(RegistryAccess.class));
    }
    @Test void rejectsOversizedUploadBeforeReassembly() {
        RegistryFriendlyByteBuf buf = buffer();
        try {
            buf.writeBlockPos(BlockPos.ZERO);
            buf.writeUUID(UUID.randomUUID());
            buf.writeVarInt(0); buf.writeVarInt(1);
            buf.writeByteArray(new byte[ChunkedTransfer.CHUNK_BYTES + 1]);
            assertThrows(DecoderException.class, () -> new UploadImageChunkC2SPacket(buf));
        } finally { buf.release(); }
    }
    @Test void rejectsOversizedDownloadBeforeReassembly() {
        RegistryFriendlyByteBuf buf = buffer();
        try {
            buf.writeUUID(UUID.randomUUID());
            buf.writeVarInt(0); buf.writeVarInt(1);
            buf.writeByteArray(new byte[ChunkedTransfer.CHUNK_BYTES + 1]);
            assertThrows(DecoderException.class, () -> new ImageDownloadChunkS2CPacket(buf));
        } finally { buf.release(); }
    }
    @Test void validMediaEnvelopesRemainWireCompatible() {
        RegistryFriendlyByteBuf upload = buffer(), download = buffer();
        try {
            UUID id = UUID.randomUUID();
            byte[] bytes = new byte[ChunkedTransfer.CHUNK_BYTES];
            new UploadImageChunkC2SPacket(BlockPos.ZERO,id,0,2,bytes,"image.png").write(upload);
            UploadImageChunkC2SPacket decodedUpload = new UploadImageChunkC2SPacket(upload);
            assertArrayEquals(bytes,decodedUpload.getData());
            assertEquals("image.png",decodedUpload.getFileName());
            new ImageDownloadChunkS2CPacket(id,0,2,bytes,"png").write(download);
            ImageDownloadChunkS2CPacket decodedDownload = new ImageDownloadChunkS2CPacket(download);
            assertArrayEquals(bytes,decodedDownload.getData());
            assertEquals("png",decodedDownload.getExtension());
        } finally { upload.release(); download.release(); }
    }
}
