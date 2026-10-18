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
    String key;
    @BeforeEach void setup(@TempDir Path root) throws Exception {
        ScreenRegistry.init(root);
        server=mock(MinecraftServer.class); level=mock(ServerLevel.class); player=mock(ServerPlayer.class);
        chunks=mock(ServerChunkCache.class); tracking=mock(ChunkMap.class); chunk=mock(LevelChunk.class);
        var field=ServerChunkCache.class.getDeclaredField("chunkMap"); field.setAccessible(true); field.set(chunks,tracking);
        when(level.getServer()).thenReturn(server); when(level.getChunkSource()).thenReturn(chunks);
        when(player.level()).thenReturn(level); when(player.getUUID()).thenReturn(visitor);
        var players=mock(PlayerList.class); when(server.getPlayerList()).thenReturn(players); when(players.getPlayer(visitor)).thenReturn(player);
        tile=mock(ScreenBlockEntity.class); when(tile.getLevel()).thenReturn(level); when(tile.getBlockPos()).thenReturn(pos);
        when(tile.getDisplayId()).thenReturn(display); when(tile.getScreenId()).thenReturn("station");
        when(tile.getImageId()).thenReturn(image); when(tile.getResolvedImageId()).thenReturn(image);
        key=ScreenNetworkService.network("station"); when(tile.accessKey()).thenReturn(key);
        assertTrue(ScreenRegistry.networks().createOwned(key,owner,image));
        media=mockStatic(ServerImageManager.class);
        var metadata=new ImageMetadata("private",image.toString(),"png",owner.toString());
        media.when(()->ServerImageManager.getImageMetadata(server,image)).thenReturn(metadata);
        ScreenAccessService.refreshMedia(tile);
    }
    @AfterEach void cleanup() { ScreenAccessService.untrack(tile); media.close(); }
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
        RequestImageDownloadC2SPacket.handle(new RequestImageDownloadC2SPacket(image),context);
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
        var capture=org.mockito.ArgumentCaptor.forClass(UUID.class);
        verify(tile).setDisplayId(capture.capture());
        State independent=ScreenRegistry.networks().snapshot(ScreenNetworkService.display(capture.getValue()));
        assertEquals(owner,independent.owner()); assertEquals(image,independent.image());
    }
}
