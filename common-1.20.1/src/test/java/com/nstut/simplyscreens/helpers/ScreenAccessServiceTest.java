package com.nstut.simplyscreens.helpers;

import com.nstut.simplyscreens.*;
import com.nstut.simplyscreens.ScreenNetworkService.*;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.network.*;
import dev.architectury.networking.NetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScreenAccessServiceTest {
    final UUID owner=UUID.randomUUID(), visitor=UUID.randomUUID(), image=UUID.randomUUID(), display=UUID.randomUUID();
    final BlockPos pos=new BlockPos(0,64,0);
    MinecraftServer server; ServerLevel level; ServerPlayer player; ScreenBlockEntity tile;
    ServerChunkCache chunks; ChunkMap tracking; LevelChunk chunk;
    MockedStatic<ServerImageManager> media;
    MockedStatic<PacketRegistries> packets;
    final List<UpdateScreenS2CPacket> updates = new ArrayList<>();
    String key;
    @BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
    @BeforeEach void setup(@TempDir Path root) throws Exception {
        ScreenRegistry.init(root);
        server=mock(MinecraftServer.class); level=mock(ServerLevel.class); player=mock(ServerPlayer.class);
        chunks=mock(ServerChunkCache.class); tracking=mock(ChunkMap.class); chunk=mock(LevelChunk.class);
        var field=ServerChunkCache.class.getDeclaredField("chunkMap"); field.setAccessible(true); field.set(chunks,tracking);
        when(level.getServer()).thenReturn(server); when(level.getChunkSource()).thenReturn(chunks);
        when(player.level()).thenReturn(level); when(player.serverLevel()).thenReturn(level); when(player.getUUID()).thenReturn(visitor);
        var players=mock(PlayerList.class); when(server.getPlayerList()).thenReturn(players); when(players.getPlayer(visitor)).thenReturn(player);
        tile=mock(ScreenBlockEntity.class); when(tile.getLevel()).thenReturn(level); when(tile.getBlockPos()).thenReturn(pos);
        when(tile.getDisplayId()).thenReturn(display); when(tile.getScreenId()).thenReturn("station");
        when(tile.getImageId()).thenReturn(image); when(tile.getResolvedImageId()).thenReturn(image);
        key=ScreenNetworkService.network("station"); when(tile.accessKey()).thenReturn(key);
        assertTrue(ScreenRegistry.networks().createOwned(key,owner,image));
        media=mockStatic(ServerImageManager.class);
        packets = mockStatic(PacketRegistries.class);
        packets.when(() -> PacketRegistries.sendToPlayer(any(), any(UpdateScreenS2CPacket.class)))
                .thenAnswer(call -> { updates.add(call.getArgument(1)); return null; });
        var metadata=new ImageMetadata("private",image.toString(),"png",owner.toString());
        media.when(()->ServerImageManager.getImageMetadata(server,image)).thenReturn(metadata);
        ScreenAccessService.refreshMedia(tile);
    }
    @AfterEach void cleanup() { ScreenAccessService.clearLevel(level); packets.close(); media.close(); }
    void tracked() {
        when(chunks.getChunkNow(0,0)).thenReturn(chunk); when(chunk.getBlockEntity(pos)).thenReturn(tile);
        when(tracking.getPlayers(any(ChunkPos.class),eq(false))).thenReturn(List.of(player));
    }
    @Test void guessedPrivateUuidCannotReadWithoutTrackedDisplay() {
        assertFalse(ScreenAccessService.canReadImage(player,image));
        verifyNoInteractions(tracking);
    }
    @Test void displayedImageCanBeReadWithoutLibraryAccessAndDoesNotGrantControl() {
        tracked();
        assertFalse(ScreenAccessService.library(player,image));
        assertTrue(ScreenAccessService.canReadImage(player,image));
        assertFalse(ScreenAccessService.allowed(player,tile,Permission.CONTROL));
        assertFalse(ScreenAccessService.allowed(player,tile,Permission.MANAGE));
    }
    @Test void viewRevocationAndContentChangeImmediatelyDenyFutureChunks() {
        tracked(); assertTrue(ScreenAccessService.canReadImage(player,image));
        assertTrue(ScreenRegistry.networks().access(key,new Actor(owner,false),0,"VIEW","OWNER_ONLY"));
        assertFalse(ScreenAccessService.canReadImage(player,image));
        assertTrue(ScreenRegistry.networks().access(key,new Actor(owner,false),1,"VIEW","PUBLIC"));
        when(tile.getResolvedImageId()).thenReturn(UUID.randomUUID());
        assertFalse(ScreenAccessService.canReadImage(player,image));
    }
    @Test void unloadDimensionChangeAndReplacedTileNeverAuthorizePrivateMedia() {
        tracked(); assertTrue(ScreenAccessService.canReadImage(player,image));
        when(chunk.getBlockEntity(pos)).thenReturn(null); assertFalse(ScreenAccessService.canReadImage(player,image));
        when(chunk.getBlockEntity(pos)).thenReturn(tile); when(tile.getLevel()).thenReturn(mock(ServerLevel.class));
        assertFalse(ScreenAccessService.canReadImage(player,image));
        when(tile.getLevel()).thenReturn(level); ScreenAccessService.untrack(tile);
        assertFalse(ScreenAccessService.canReadImage(player,image));
    }
    @Test void ownerCanDownloadUnusedPrivateMedia() {
        media.when(()->ServerImageManager.getImageMetadata(server,image)).thenReturn(new ImageMetadata("mine",image.toString(),"png",visitor.toString()));
        assertTrue(ScreenAccessService.canReadImage(player,image)); verifyNoInteractions(tracking);
    }
    @Test void untrackedChunksAndDisconnectedPlayerAreDenied() {
        tracked(); when(tracking.getPlayers(any(ChunkPos.class),eq(false))).thenReturn(List.of());
        assertFalse(ScreenAccessService.canReadImage(player,image));
        when(server.getPlayerList().getPlayer(visitor)).thenReturn(null);
        assertFalse(ScreenAccessService.canReadImage(player,image));
    }
    @Test void privateDownloadPacketDoesNotScheduleOrProbeFiles() {
        var context=mock(NetworkManager.PacketContext.class); when(context.getPlayer()).thenReturn(player);
        doAnswer(call->{call.getArgument(0,Runnable.class).run(); return null;}).when(context).queue(any(Runnable.class));
        new RequestImageDownloadC2SPacket(image).handle(() -> context);
        media.verify(()->ServerImageManager.getImageFilePath(any(),any()),never());
    }
    @Test void trustedControlCannotRelinkOrManageOwnership() {
        ScreenRegistry.networks().access(key,new Actor(owner,false),0,"TRUST",visitor.toString());
        ScreenRegistry.networks().access(key,new Actor(owner,false),1,"CONTROL","TRUSTED");
        assertTrue(ScreenAccessService.allowed(player,tile,Permission.CONTROL));
        assertFalse(ScreenAccessService.relink(player,tile,"other"));
        verify(tile,never()).setScreenId(anyString());
        assertNull(ScreenRegistry.networks().snapshot(ScreenNetworkService.network("other")));
    }
    @Test void standaloneDisplaysAreProtectedAndCannotMergeAcrossOwners() {
        var other=mock(ScreenBlockEntity.class); String standalone=ScreenNetworkService.display(display);
        when(other.accessKey()).thenReturn(standalone); ScreenRegistry.networks().createOwned(standalone,visitor,null);
        assertFalse(ScreenAccessService.canMerge(tile,other));
        assertFalse(ScreenRegistry.networks().allowed(standalone,new Actor(owner,false),Permission.MANAGE));
    }
    @Test void localImageSelectionLeavesNetworkContentUnchanged() {
        when(player.getUUID()).thenReturn(owner);
        when(tile.isLocalImageOverride()).thenReturn(true);
        assertTrue(ScreenAccessService.select(player,tile,null));
        assertEquals(image,ScreenRegistry.networks().snapshot(key).image());
        verify(tile).setImageId(null);
    }
    @Test void relinkDeniesForeignDestinationBeforeChangingSource() {
        when(player.getUUID()).thenReturn(owner);
        String destination=ScreenNetworkService.network("foreign");
        ScreenRegistry.networks().createOwned(destination,visitor,null);
        assertFalse(ScreenAccessService.relink(player,tile,"foreign"));
        verify(tile,never()).setScreenId(anyString());
        assertEquals(image,ScreenRegistry.networks().snapshot(key).image());
    }
    @Test void detachPreservesCurrentImageAndInheritedPermissionsAfterTransfer() {
        when(player.getUUID()).thenReturn(owner);
        assertTrue(ScreenAccessService.relink(player,tile,""));
        verify(tile, never()).setDisplayId(any());
        State independent=ScreenRegistry.networks().snapshot(ScreenNetworkService.display(display));
        assertEquals(owner,independent.owner()); assertEquals(image,independent.image());
    }
    @Test void unloadedChildFollowsDetachedIdentityWithoutLoadedAnchor() throws Exception {
        when(player.getUUID()).thenReturn(owner);
        ScreenBlockEntity anchor = persistedTile(pos, pos);
        ScreenRegistry.updateDisplay(display, "station", false, false);
        UUID latest = UUID.randomUUID();
        assertTrue(ScreenRegistry.networks().select(key, new Actor(owner, false), latest, 0));
        // No loaded children exist while the anchor is detached.
        assertTrue(ScreenAccessService.relink(player, anchor, ""));
        assertEquals(display, anchor.getDisplayId());
        String independent = ScreenNetworkService.display(display);
        State detached = ScreenRegistry.networks().snapshot(independent);
        assertEquals(latest, detached.image());
        assertTrue(ScreenRegistry.networks().access(independent, new Actor(owner, false),
                detached.revision(), "VIEW", "OWNER_ONLY"));
        assertTrue(ScreenRegistry.networks().select(key, new Actor(owner, false), image, 1));
        // Load a child's stale saved link later, with no anchor chunk available.
        ScreenBlockEntity child = persistedTile(pos.offset(16, 0, 0), pos);
        var restore = ScreenBlockEntity.class.getDeclaredMethod("restoreDisplayState");
        restore.setAccessible(true);
        restore.invoke(child);
        assertEquals(display, child.getDisplayId());
        assertEquals("", child.getScreenId());
        assertEquals(latest, child.getImageId());
        assertEquals(latest, child.getResolvedImageId());
        assertFalse(ScreenRegistry.networks().allowed(child.accessKey(), new Actor(visitor, false), Permission.VIEW));
        assertTrue(ScreenRegistry.networks().allowed(key, new Actor(visitor, false), Permission.VIEW));
    }
    @Test void linkedChangeAndClearReachLoadedChildWithoutItsAnchor() throws Exception {
        ScreenBlockEntity child = loadedChild(image, false);
        UUID replacement = UUID.randomUUID();
        assertTrue(ScreenAccessService.canReadImage(player, image));
        // Selection happens on a different physical display; this child's own anchor remains unloaded.
        BlockPos remotePos = pos.offset(32, 0, 0);
        ScreenBlockEntity remote = persistedTile(remotePos, remotePos);
        remote.setDisplayId(UUID.randomUUID());
        ScreenRegistry.updateDisplay(remote.getDisplayId(), "station", false, false);
        when(server.getAllLevels()).thenReturn(List.of(level));
        media.when(() -> ServerImageManager.getImageMetadata(server, replacement))
                .thenReturn(new ImageMetadata("new", replacement.toString(), "png", owner.toString()));
        when(player.getUUID()).thenReturn(owner);
        assertTrue(ScreenAccessService.select(player, remote, replacement));
        when(player.getUUID()).thenReturn(visitor);
        assertEquals(replacement, child.getImageId());
        assertTrue(ScreenAccessService.canReadImage(player, replacement));
        assertFalse(ScreenAccessService.canReadImage(player, image));
        ScreenRegistry.setImageId("station", null);
        assertNull(child.getImageId());
        assertFalse(ScreenAccessService.canReadImage(player, replacement));
        ScreenRegistry.setImageId("station", image);
        assertEquals(image, child.getImageId());
        assertTrue(ScreenAccessService.canReadImage(player, image));
        assertEquals(Arrays.asList(replacement, null, image), clientImages(child.getBlockPos()));
        verify(level, never()).getChunkAt(any(BlockPos.class));
        verify(child, never()).getAnchorEntity();
    }
    @Test void firstSelectionReachesInitiallyBlankAnchorlessChild() throws Exception {
        assertTrue(ScreenRegistry.networks().select(key, new Actor(owner, false), null, 0));
        ScreenBlockEntity child = loadedChild(null, false);
        ScreenRegistry.reconcileContent(key, image);
        assertEquals(image, child.getImageId());
        assertTrue(ScreenAccessService.canReadImage(player, image));
        assertEquals(List.of(image), clientImages(child.getBlockPos()));
    }
    @Test void linkedChangePreservesChildLocalOverride() throws Exception {
        UUID local = UUID.randomUUID();
        assertTrue(ScreenRegistry.networks().createOwned(ScreenNetworkService.display(display), owner, local));
        ScreenBlockEntity child = loadedChild(local, true);
        ScreenRegistry.setImageId("station", UUID.randomUUID());
        assertEquals(local, child.getImageId());
        assertEquals(local, child.getResolvedImageId());
        assertTrue(ScreenAccessService.canReadImage(player, local));
        assertTrue(clientImages(child.getBlockPos()).isEmpty());
    }
    @Test void linkedChangeIgnoresReplacedOrUnloadedChildInstances() throws Exception {
        ScreenBlockEntity child = loadedChild(image, false);
        when(chunk.getBlockEntity(child.getBlockPos())).thenReturn(null);
        ScreenRegistry.setImageId("station", UUID.randomUUID());
        assertEquals(image, child.getImageId());
        assertTrue(clientImages(child.getBlockPos()).isEmpty());
        // The stale reference was removed, so reappearing without a load cannot receive an update.
        when(chunk.getBlockEntity(child.getBlockPos())).thenReturn(child);
        ScreenRegistry.setImageId("station", UUID.randomUUID());
        assertEquals(image, child.getImageId());
    }
    private List<UUID> clientImages(BlockPos position) {
        return updates.stream().filter(packet -> position.equals(packet.pos)).map(packet -> packet.imageId).toList();
    }
    private ScreenBlockEntity loadedChild(UUID initialImage, boolean override) throws Exception {
        ScreenBlockEntity child = persistedTile(pos.offset(16, 0, 0), pos);
        ScreenRegistry.updateDisplay(display, "station", override, false);
        var savedImage = ScreenBlockEntity.class.getDeclaredField("imageId");
        savedImage.setAccessible(true);
        savedImage.set(child, initialImage);
        when(chunks.getChunkNow(1, 0)).thenReturn(chunk);
        when(chunk.getBlockEntity(child.getBlockPos())).thenReturn(child);
        when(tracking.getPlayers(any(ChunkPos.class), eq(false))).thenReturn(List.of(player));
        ScreenAccessService.refreshMedia(child);
        assertFalse(child.isAnchor());
        return child;
    }
    private ScreenBlockEntity persistedTile(BlockPos position, BlockPos anchor) throws Exception {
        ScreenBlockEntity screen = mock(ScreenBlockEntity.class, CALLS_REAL_METHODS);
        screen.setDisplayId(display);
        screen.setAnchorPos(anchor);
        var base = net.minecraft.world.level.block.entity.BlockEntity.class;
        var savedPosition = base.getDeclaredField("worldPosition");
        savedPosition.setAccessible(true);
        savedPosition.set(screen, position);
        var savedLevel = base.getDeclaredField("level");
        savedLevel.setAccessible(true);
        savedLevel.set(screen, level);
        var savedLink = ScreenBlockEntity.class.getDeclaredField("screenId");
        savedLink.setAccessible(true);
        savedLink.set(screen, "station");
        var savedImage = ScreenBlockEntity.class.getDeclaredField("imageId");
        savedImage.setAccessible(true);
        savedImage.set(screen, image);
        doReturn(mock(net.minecraft.world.level.block.state.BlockState.class)).when(screen).getBlockState();
        doNothing().when(screen).setChanged();
        return screen;
    }
    @Test void physicalSplitDetachesNetworkAndCopiesAuthoritativeContent() throws Exception {
        ScreenBlockEntity split = mock(ScreenBlockEntity.class, CALLS_REAL_METHODS);
        split.setDisplayId(display);
        var link = ScreenBlockEntity.class.getDeclaredField("screenId");
        link.setAccessible(true);
        link.set(split, "station");
        UUID latest = UUID.randomUUID();
        assertTrue(ScreenRegistry.networks().select(key, new Actor(owner, false), latest, 0));
        var fork = ScreenBlockEntity.class.getDeclaredMethod("forkDisplayIdentity", ScreenBlockEntity.class);
        fork.setAccessible(true);
        fork.invoke(split, split);
        assertNotEquals(display, split.getDisplayId());
        assertEquals("", split.getScreenId());
        assertFalse(split.isLocalImageOverride());
        assertEquals(latest, split.getResolvedImageId());
        State independent = ScreenRegistry.networks().snapshot(split.accessKey());
        assertEquals(owner, independent.owner());
        assertTrue(ScreenRegistry.networks().select(split.accessKey(), new Actor(owner, false), null, independent.revision()));
        assertEquals(latest, ScreenRegistry.networks().snapshot(key).image());
        assertNull(split.getResolvedImageId());
    }

    @Test void failedOverrideSaveRestoresRawFlag() throws Exception {
        ScreenBlockEntity real = mock(ScreenBlockEntity.class, CALLS_REAL_METHODS);
        real.setDisplayId(display);
        var override = ScreenBlockEntity.class.getDeclaredField("localImageOverride");
        override.setAccessible(true);
        override.setBoolean(real, true);
        var link = ScreenBlockEntity.class.getDeclaredField("screenId");
        link.setAccessible(true);
        link.set(real, "station");
        try (var registry = mockStatic(ScreenRegistry.class)) {
            registry.when(() -> ScreenRegistry.updateDisplay(display, "station", false, false))
                    .thenThrow(new IllegalStateException("disk unavailable"));
            assertThrows(IllegalStateException.class, () -> real.useLocalImage(false));
            assertTrue(override.getBoolean(real));
            registry.verify(() -> ScreenRegistry.reconcileContent(anyString(), any()), never());
        }
    }

    @Test void selfSynchronizationPreservesDeferredIdentityFork() throws Exception {
        ScreenBlockEntity split = mock(ScreenBlockEntity.class, CALLS_REAL_METHODS);
        split.setDisplayId(display);
        var pending = ScreenBlockEntity.class.getDeclaredField("needsIdentityFork");
        pending.setAccessible(true);
        pending.setBoolean(split, true);
        split.copyDisplayIdentity(split);
        assertTrue(pending.getBoolean(split));
        assertEquals(display, split.getDisplayId());
    }

}
