package com.nstut.simplyscreens.helpers;

import com.nstut.packetweave.api.TransferLimits;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class MediaDownloadReceiverTest {
    final AtomicLong clock = new AtomicLong();
    MediaDownloadReceiver receiver() {
        return new MediaDownloadReceiver(new TransferLimits(4, 12, 3, 3, 24, Duration.ofNanos(10)), clock::get);
    }

    @Test void reconstructsReorderedChunksAndPairsFirstChunkMetadata() {
        MediaDownloadReceiver receiver = receiver();
        UUID id = UUID.randomUUID();
        byte[] tail = {5,6};
        assertNull(receiver.accept(id,1,2,tail,null));
        tail[0] = 99;
        MediaDownloadReceiver.Download image = receiver.accept(id,0,2,new byte[]{1,2,3,4},"png");
        assertNotNull(image);
        assertEquals("png",image.extension());
        assertArrayEquals(new byte[]{1,2,3,4,5,6},image.bytes());
        assertFalse(receiver.isPending(id));
    }

    @Test void invalidCountsIndicesDuplicatesAndChangedCountsAbort() {
        MediaDownloadReceiver receiver = receiver();
        UUID id = UUID.randomUUID();
        assertNull(receiver.accept(id,0,Integer.MAX_VALUE,new byte[]{1},"png"));
        assertFalse(receiver.isPending(id));
        assertNull(receiver.accept(id,-1,2,new byte[]{1},null));
        assertFalse(receiver.isPending(id));
        assertNull(receiver.accept(id,0,2,new byte[]{1},"png"));
        assertNull(receiver.accept(id,0,2,new byte[]{2},"png"));
        assertFalse(receiver.isPending(id));
        assertNull(receiver.accept(id,0,2,new byte[]{1},"png"));
        assertNull(receiver.accept(id,1,3,new byte[]{2},null));
        assertFalse(receiver.isPending(id));
    }

    @Test void rejectsUnsafeExtensionsAndOversizedPayloadsBeforeCaching() {
        MediaDownloadReceiver receiver = receiver();
        UUID id = UUID.randomUUID();
        for (String extension : new String[]{null,"../png","webp","PNG",""}) {
            assertNull(receiver.accept(id,0,1,new byte[]{1},extension));
            assertFalse(receiver.isPending(id));
        }
        assertNull(receiver.accept(id,0,1,new byte[5],"jpg"));
        assertFalse(receiver.isPending(id));
        assertNull(receiver.accept(id,0,1,new byte[0],"jpg"));
        assertFalse(receiver.isPending(id));
    }

    @Test void globalLimitExpiryInvalidationAndDisconnectPermitRetry() {
        MediaDownloadReceiver receiver = receiver();
        UUID[] ids = {UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()};
        for (int i=0;i<3;i++) assertNull(receiver.accept(ids[i],0,2,new byte[]{1},"png"));
        assertNull(receiver.accept(ids[3],0,2,new byte[]{1},"png"));
        assertFalse(receiver.isPending(ids[3]));
        receiver.cancel(ids[0]);
        assertNull(receiver.accept(ids[3],0,2,new byte[]{1},"png"));
        assertTrue(receiver.isPending(ids[3]));
        clock.set(10);
        assertEquals(3,receiver.reapExpired().size());
        assertFalse(receiver.isPending(ids[3]));
        assertNotNull(receiver.accept(ids[3],0,1,new byte[]{7},"jpg"));
        receiver.accept(ids[0],0,2,new byte[]{1},"png");
        receiver.clear();
        assertFalse(receiver.isPending(ids[0]));
        assertNotNull(receiver.accept(ids[0],0,1,new byte[]{8},"jpeg"));
    }
}
