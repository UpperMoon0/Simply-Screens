package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.helpers.ServerImageManager;
import com.nstut.packetweave.api.OutboundTransfers;
import dev.architectury.networking.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImageDownloadPreparationTest {
    @TempDir Path directory;
    MinecraftServer server;
    ServerPlayer player;
    NetworkManager.PacketContext context;
    UUID owner = UUID.randomUUID(), image = UUID.randomUUID();
    LinkedBlockingQueue<Runnable> callbacks;
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
    }
    @BeforeEach void setup() throws Exception {
        server = mock(MinecraftServer.class); player = mock(ServerPlayer.class);
        var level = mock(ServerLevel.class); var players = mock(PlayerList.class);
        when(server.isRunning()).thenReturn(true); when(server.getPlayerList()).thenReturn(players);
        when(player.getUUID()).thenReturn(owner); when(players.getPlayer(owner)).thenReturn(player);
        when(player.getServer()).thenReturn(server); when(player.level()).thenReturn(level);
        when(level.getServer()).thenReturn(server);
        Thread game = Thread.currentThread();
        when(server.getWorldPath(any(LevelResource.class))).thenAnswer(call -> {
            assertNotSame(game, Thread.currentThread(), "Download preparation must not read files on the game thread");
            return directory;
        });
        callbacks = new LinkedBlockingQueue<>();
        doAnswer(call -> { callbacks.add(call.getArgument(0)); return null; }).when(server).execute(any(Runnable.class));
        context = mock(NetworkManager.PacketContext.class); when(context.getPlayer()).thenReturn(player);
        doAnswer(call -> { ((Runnable)call.getArgument(0)).run(); return null; }).when(context).queue(any());
        Path images = directory.resolve("simply_screens_images"); Files.createDirectories(images);
        Files.write(images.resolve(image + ".png"), new byte[]{1});
        Files.writeString(images.resolve(image + ".json"), "{\"name\":\"fixture\",\"id\":\"" + image
                + "\",\"extension\":\"png\",\"ownerUUID\":\"" + owner + "\"}");
    }
    @AfterEach void clear() { RequestImageDownloadC2SPacket.cancelServer(server); }
    void request() { new RequestImageDownloadC2SPacket(image).handle(() -> context); }
    Runnable prepared() throws Exception {
        Runnable work = callbacks.poll(10, TimeUnit.SECONDS); assertNotNull(work); return work;
    }
    @Test void filePreparationRunsOnWorkerAndDisconnectRevokesQueuedHandoff() throws Exception {
        request();
        Runnable work = prepared();
        try (var outgoing = mockStatic(OutboundTransfers.class); var packets = mockStatic(PacketRegistries.class)) {
            RequestImageDownloadC2SPacket.cancelPending(player);
            work.run();
            outgoing.verifyNoInteractions(); packets.verifyNoInteractions();
        }
    }
    @Test void stoppedServerRevokesQueuedPreparation() throws Exception {
        request(); Runnable work = prepared();
        try (var outgoing = mockStatic(OutboundTransfers.class); var packets = mockStatic(PacketRegistries.class)) {
            RequestImageDownloadC2SPacket.cancelServer(server);
            work.run();
            outgoing.verifyNoInteractions(); packets.verifyNoInteractions();
        }
    }
    @Test void twoPreparationsPerPeerAndGlobalAdmissionAreBounded() throws Exception {
        request(); Runnable work = prepared();
        request(); Runnable second = prepared();
        try (var packets = mockStatic(PacketRegistries.class)) {
            request();
            packets.verify(() -> PacketRegistries.sendToPlayer(eq(player), any(ImageDownloadChunkS2CPacket.class)));
        }
        var field = RequestImageDownloadC2SPacket.class.getDeclaredField("PREPARING"); field.setAccessible(true);
        @SuppressWarnings("unchecked") var pending = (java.util.Map<Object, ServerPlayer>)field.get(null);
        Class<?> type = Class.forName(RequestImageDownloadC2SPacket.class.getName() + "$Preparation");
        var constructor = type.getDeclaredConstructor(MinecraftServer.class); constructor.setAccessible(true);
        synchronized (pending) { for (int i=2;i<32;i++) pending.put(constructor.newInstance(server), mock(ServerPlayer.class)); }
        var other = mock(ServerPlayer.class); UUID otherId = UUID.randomUUID();
        when(other.getUUID()).thenReturn(otherId); when(other.getServer()).thenReturn(server);
        when(server.getPlayerList().getPlayer(otherId)).thenReturn(other);
        when(context.getPlayer()).thenReturn(other);
        try (var packets = mockStatic(PacketRegistries.class)) {
            request();
            packets.verify(() -> PacketRegistries.sendToPlayer(eq(other), any(ImageDownloadChunkS2CPacket.class)));
        }
        assertEquals(32, pending.size());
        RequestImageDownloadC2SPacket.cancelServer(server); work.run(); second.run();
        assertTrue(pending.isEmpty());
    }
    @Test void legitimatePreparedDownloadUsesLiveAccessWithoutMoreFilesystemReads() throws Exception {
        request(); Runnable work = prepared();
        var sender = mock(OutboundTransfers.class);
        when(sender.start(eq(player), any(UUID.class), any(Path.class), anyLong(), any(), any(), any())).thenReturn(true);
        try (var outgoing = mockStatic(OutboundTransfers.class); var media = mockStatic(ServerImageManager.class)) {
            outgoing.when(OutboundTransfers::shared).thenReturn(sender);
            work.run();
            verify(sender).start(eq(player), any(UUID.class), eq(directory.resolve("simply_screens_images").resolve(image + ".png")),
                    anyLong(), any(), any(), any());
            media.verifyNoInteractions();
        }
    }

}
