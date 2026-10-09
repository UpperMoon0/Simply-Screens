package com.nstut.simplyscreens.helpers;

import com.nstut.packetweave.api.TransferId;
import com.nstut.packetweave.api.TransferRegistry;
import com.nstut.simplyscreens.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServerMediaTransferTest {
    final UUID owner = UUID.randomUUID();
    final BlockPos screen = new BlockPos(1,2,3);
    ServerPlayer player;
    MockedStatic<ScreenPacketSecurity> permission;
    boolean uploadsDisabled;
    TransferRegistry transfers() throws Exception {
        var field = ServerImageManager.class.getDeclaredField("UPLOAD_TRANSFERS");
        field.setAccessible(true);
        return (TransferRegistry) field.get(null);
    }
    TransferId id(UUID transaction) { return new TransferId("simply_screens_upload",owner,transaction); }
    @BeforeEach void setup() {
        uploadsDisabled = Config.DISABLE_UPLOAD;
        Config.DISABLE_UPLOAD = false;
        player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(owner);
        when(player.level()).thenReturn(mock(ServerLevel.class));
        permission = mockStatic(ScreenPacketSecurity.class);
        permission.when(() -> ScreenPacketSecurity.canModify(eq(player),any(BlockPos.class))).thenReturn(true);
    }
    @AfterEach void cleanup() {
        ServerImageManager.cancelUploads(owner);
        permission.close();
        Config.DISABLE_UPLOAD = uploadsDisabled;
    }
    @Test void changedScreenAndChunkCountAbortOriginalTransfer() throws Exception {
        UUID transaction = UUID.randomUUID();
        ServerImageManager.handleImageChunk(player,screen,transaction,0,2,new byte[20],"image.png");
        assertTrue(transfers().contains(id(transaction)));
        ServerImageManager.handleImageChunk(player,screen.offset(1,0,0),transaction,1,2,new byte[20],null);
        assertFalse(transfers().contains(id(transaction)));
        ServerImageManager.handleImageChunk(player,screen,transaction,0,2,new byte[20],"image.png");
        ServerImageManager.handleImageChunk(player,screen,transaction,1,3,new byte[20],null);
        assertFalse(transfers().contains(id(transaction)));
    }
    @Test void permissionRevocationCancelsRetainedBytes() throws Exception {
        UUID transaction = UUID.randomUUID();
        ServerImageManager.handleImageChunk(player,screen,transaction,0,2,new byte[20],"image.png");
        permission.when(() -> ScreenPacketSecurity.canModify(eq(player),any(BlockPos.class))).thenReturn(false);
        ServerImageManager.handleImageChunk(player,screen,transaction,1,2,new byte[20],null);
        assertFalse(transfers().contains(id(transaction)));
        assertEquals(0,transfers().bufferedBytes());
    }
    @Test void duplicateChunksAbortAndDisconnectReleasesOwnerSessions() throws Exception {
        UUID transaction = UUID.randomUUID();
        ServerImageManager.handleImageChunk(player,screen,transaction,0,2,new byte[20],"image.png");
        ServerImageManager.handleImageChunk(player,screen,transaction,0,2,new byte[20],"image.png");
        assertFalse(transfers().contains(id(transaction)));
        for (int i=0;i<4;i++) ServerImageManager.handleImageChunk(player,screen,UUID.randomUUID(),0,2,new byte[20],"image.png");
        UUID excess = UUID.randomUUID();
        ServerImageManager.handleImageChunk(player,screen,excess,0,2,new byte[20],"image.png");
        assertFalse(transfers().contains(id(excess)));
        ServerImageManager.cancelUploads(owner);
        assertEquals(0,transfers().bufferedBytes());
        ServerImageManager.handleImageChunk(player,screen,excess,0,2,new byte[20],"image.png");
        assertTrue(transfers().contains(id(excess)));
    }
}
