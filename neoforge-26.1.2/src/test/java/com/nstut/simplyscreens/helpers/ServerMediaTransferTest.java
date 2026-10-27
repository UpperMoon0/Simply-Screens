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
    @Test void transferBookkeepingDoesNotWaitForImageStorageMonitor() throws Exception {
        transfers(); // Initialize static state before another thread enters the manager.
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            synchronized (ServerImageManager.class) {
                var work = executor.submit(() -> {
                    try (var workerPermission = mockStatic(ScreenPacketSecurity.class)) {
                        workerPermission.when(() -> ScreenPacketSecurity.canModify(eq(player), any(BlockPos.class))).thenReturn(true);
                        UUID transaction = UUID.randomUUID();
                        ServerImageManager.handleImageChunk(player, screen, transaction, 0, 2, new byte[20], "image.png");
                        assertTrue(transfers().contains(id(transaction)));
                        workerPermission.when(() -> ScreenPacketSecurity.canModify(eq(player), any(BlockPos.class))).thenReturn(false);
                        ServerImageManager.handleImageChunk(player, screen, transaction, 1, 2, new byte[20], null);
                        assertFalse(transfers().contains(id(transaction)));
                        for (String method : new String[]{"reapUploads", "clearUploads"}) {
                            var action = ServerImageManager.class.getDeclaredMethod(method);
                            action.setAccessible(true);
                            action.invoke(null);
                        }
                        ServerImageManager.cancelUploads(owner);
                        var acquire = ServerImageManager.class.getDeclaredMethod("tryAcquireProcessingSlot", UUID.class, long.class);
                        var release = ServerImageManager.class.getDeclaredMethod("releaseProcessingSlot", UUID.class, long.class);
                        acquire.setAccessible(true);
                        release.setAccessible(true);
                        assertEquals(true, acquire.invoke(null, owner, 20L));
                        release.invoke(null, owner, 20L);
                    }
                    return null;
                });
                // This is the same monitor held throughout image decoding and disk I/O.
                work.get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test void queuedUploadGenerationIsRevokedOnDisconnectAndServerStop() throws Exception {
        var stateType = Class.forName(ServerImageManager.class.getName() + "$UploadState");
        var constructor = stateType.getDeclaredConstructor(UUID.class, ServerLevel.class, BlockPos.class,
                int.class, Object.class, long.class);
        constructor.setAccessible(true);
        var connectionsField = ServerImageManager.class.getDeclaredField("UPLOAD_CONNECTIONS");
        connectionsField.setAccessible(true);
        @SuppressWarnings("unchecked") var connections = (java.util.Map<UUID, Object>) connectionsField.get(null);
        var epochField = ServerImageManager.class.getDeclaredField("transferEpoch");
        epochField.setAccessible(true);
        var current = ServerImageManager.class.getDeclaredMethod("isCurrentUpload", stateType);
        current.setAccessible(true);
        Object token = new Object();
        connections.put(owner, token);
        Object state = constructor.newInstance(owner, player.level(), screen, 1, token, epochField.getLong(null));
        assertEquals(true, current.invoke(null, state));
        ServerImageManager.cancelUploads(owner);
        assertEquals(false, current.invoke(null, state));
        connections.put(owner, new Object()); // A reconnect must not revive queued work.
        assertEquals(false, current.invoke(null, state));
        Object next = constructor.newInstance(owner, player.level(), screen, 1, connections.get(owner), epochField.getLong(null));
        assertEquals(true, current.invoke(null, next));
        var clear = ServerImageManager.class.getDeclaredMethod("clearUploads");
        clear.setAccessible(true);
        clear.invoke(null);
        assertEquals(false, current.invoke(null, next));
    }

    @Test void disconnectedUploadWaitingForStorageDoesNotWrite() throws Exception {
        var stateType = Class.forName(ServerImageManager.class.getName() + "$UploadState");
        var constructor = stateType.getDeclaredConstructor(UUID.class, ServerLevel.class, BlockPos.class,
                int.class, Object.class, long.class);
        constructor.setAccessible(true);
        var connectionsField = ServerImageManager.class.getDeclaredField("UPLOAD_CONNECTIONS");
        connectionsField.setAccessible(true);
        @SuppressWarnings("unchecked") var connections = (java.util.Map<UUID, Object>) connectionsField.get(null);
        var epochField = ServerImageManager.class.getDeclaredField("transferEpoch");
        epochField.setAccessible(true);
        Object token = new Object();
        connections.put(owner, token);
        Object state = constructor.newInstance(owner, player.level(), screen, 1, token, epochField.getLong(null));
        var imageData = stateType.getDeclaredField("imageData");
        imageData.setAccessible(true);
        imageData.set(state, new byte[16]);
        var acquire = ServerImageManager.class.getDeclaredMethod("tryAcquireProcessingSlot", UUID.class, long.class);
        acquire.setAccessible(true);
        assertEquals(true, acquire.invoke(null, owner, 16L));
        var finish = ServerImageManager.class.getDeclaredMethod("finishImageUpload", net.minecraft.server.MinecraftServer.class,
                ServerPlayer.class, BlockPos.class, stateType, String.class);
        finish.setAccessible(true);
        var server = mock(net.minecraft.server.MinecraftServer.class);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> work;
            var entered = new java.util.concurrent.CountDownLatch(1);
            synchronized (ServerImageManager.class) {
                work = executor.submit(() -> {
                    entered.countDown();
                    try { finish.invoke(null, server, player, screen, state, owner.toString()); }
                    catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
                });
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                ServerImageManager.cancelUploads(owner);
            }
            work.get(5, java.util.concurrent.TimeUnit.SECONDS);
            verifyNoInteractions(server);
        } finally { executor.shutdownNow(); }
    }

}
