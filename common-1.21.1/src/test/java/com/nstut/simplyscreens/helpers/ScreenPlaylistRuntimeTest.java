package com.nstut.simplyscreens.helpers;

import com.nstut.simplyscreens.*;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.network.*;
import com.nstut.simplyscreens.playlist.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScreenPlaylistRuntimeTest {
    @TempDir Path root;
    UUID actor=UUID.randomUUID(), image=UUID.randomUUID(), foreign=UUID.randomUUID();
    String key=ScreenNetworkService.network("playlist-test");
    ServerPlayer player; ScreenBlockEntity tile;
    MockedStatic<ScreenPacketSecurity> nearby; MockedStatic<ScreenAccessService> access; MockedStatic<PacketRegistries> packets;
    PlaylistWire.View latest; long requestId;
    @BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
    @BeforeEach void setup() {
        ScreenRegistry.init(root); ScreenRegistry.networks().createOwned(key,actor,null);
        player=mock(ServerPlayer.class);tile=mock(ScreenBlockEntity.class);
        when(player.getUUID()).thenReturn(actor);when(tile.contentKey()).thenReturn(key);when(tile.accessKey()).thenReturn(key);
        nearby=mockStatic(ScreenPacketSecurity.class); access=mockStatic(ScreenAccessService.class);packets=mockStatic(PacketRegistries.class);
        nearby.when(()->ScreenPacketSecurity.nearby(player,BlockPos.ZERO)).thenReturn(tile);
        access.when(()->ScreenAccessService.allowed(player,tile,ScreenNetworkService.Permission.VIEW)).thenReturn(true);
        access.when(()->ScreenAccessService.allowed(player,tile,ScreenNetworkService.Permission.CONTROL)).thenReturn(true);
        access.when(()->ScreenAccessService.state(tile)).thenAnswer(i->ScreenRegistry.networks().snapshot(key));
        access.when(()->ScreenAccessService.library(player,image)).thenReturn(true);
        packets.when(()->PacketRegistries.sendToPlayer(eq(player),any(ScreenPlaylistS2CPacket.class))).thenAnswer(i->{
            latest=PlaylistWire.View.parse(((ScreenPlaylistS2CPacket)i.getArgument(1)).json);return null;
        });
    }
    @AfterEach void cleanup() { packets.close();access.close();nearby.close();ScreenPlaylistRuntime.shutdown(); }
    ScreenPlaylistService store() {return ScreenPlaylistRuntime.service();}
    void send(String action,UUID list,UUID asset,int index,int to,long duration,String value) {
        var request=new PlaylistWire.Request(key,ScreenRegistry.networks().snapshot(key).revision(),store().library(actor).revision(),
                store().snapshot(key).revision(),++requestId,action,list,asset,index,to,duration,value);
        ScreenPlaylistRuntime.handle(player,BlockPos.ZERO,request.json());
    }
    UUID playlist() {
        send("CREATE",null,null,-1,-1,0,"Slides");assertTrue(latest.success());UUID id=latest.selected();
        send("ADD",id,image,-1,-1,1000,"");assertTrue(latest.success());return id;
    }
    @Test void authorizedCrudAndPlayUseRealStoreAndAuthoritativeContent() {
        UUID id=playlist();send("PLAY",id,null,-1,-1,0,"");
        assertTrue(latest.success());assertEquals(image,ScreenRegistry.networks().snapshot(key).image());
        assertEquals("PLAYING",latest.status());
        send("PAUSE",id,null,-1,-1,0,"");assertEquals("PAUSED",latest.status());
        send("DELETE",id,null,-1,-1,0,"");assertTrue(store().library(actor).playlists().isEmpty());
        assertEquals(image,store().snapshot(key).currentImage());
    }
    @Test void viewOnlyCannotCreateOrControlAndDenialReturnsLatest() {
        access.when(()->ScreenAccessService.allowed(player,tile,ScreenNetworkService.Permission.CONTROL)).thenReturn(false);
        send("CREATE",null,null,-1,-1,0,"Forbidden");assertFalse(latest.success());assertFalse(latest.control());
        assertTrue(store().library(actor).playlists().isEmpty());assertEquals(0,store().snapshot(key).revision());
    }
    @Test void missingViewOrDistantTargetProducesNoLibraryDisclosure() {
        access.when(()->ScreenAccessService.allowed(player,tile,ScreenNetworkService.Permission.VIEW)).thenReturn(false);
        send("GET",null,null,-1,-1,0,"");assertNull(latest);packets.verifyNoInteractions();
        access.when(()->ScreenAccessService.allowed(player,tile,ScreenNetworkService.Permission.VIEW)).thenReturn(true);
        nearby.when(()->ScreenPacketSecurity.nearby(player,BlockPos.ZERO)).thenReturn(null);
        send("CREATE",null,null,-1,-1,0,"Forbidden");assertNull(latest);
    }
    @Test void foreignPrivateImageCannotBeAddedQueuedOrAnnounced() {
        UUID id=playlist();
        for(String action:List.of("ADD","QUEUE_NEXT","ANNOUNCE")) {
            send(action,id,foreign,-1,-1,1000,"");assertFalse(latest.success(),action);
        }
        assertEquals(1,store().library(actor).find(id).slides().size());assertNull(store().snapshot(key).currentImage());
    }
    @Test void staleLibraryRevisionAndRelinkedTargetRejectMutation() {
        UUID id=playlist();
        var stale=new PlaylistWire.Request(key,0,0,0,++requestId,"DELETE",id,null,-1,-1,0,"");
        ScreenPlaylistRuntime.handle(player,BlockPos.ZERO,stale.json());assertFalse(latest.success());assertNotNull(store().library(actor).find(id));
        var wrong=new PlaylistWire.Request("network/other",0,store().library(actor).revision(),0,++requestId,"PLAY",id,null,-1,-1,0,"");
        ScreenPlaylistRuntime.handle(player,BlockPos.ZERO,wrong.json());assertFalse(latest.success());assertNull(store().snapshot(key).currentImage());
    }
    @Test void staticSelectionDiscardsTimelineSoRestartCannotOverwriteIt() {
        UUID id=playlist();send("PLAY",id,null,-1,-1,0,"");
        UUID staticImage=UUID.randomUUID();ScreenRegistry.reconcileContent(key,staticImage);ScreenPlaylistRuntime.stop(key);
        ScreenPlaylistRuntime.shutdown();ScreenPlaylistRuntime.init(root);
        assertEquals(staticImage,ScreenRegistry.networks().snapshot(key).image());assertFalse(store().snapshots().containsKey(key));
    }
    @Test void localOverrideUsesPhysicalContentKeyNotSharedNetwork() {
        UUID id=playlist();String display=ScreenNetworkService.display(UUID.randomUUID());
        ScreenRegistry.networks().createOwned(display,actor,null);when(tile.contentKey()).thenReturn(display);
        var request=new PlaylistWire.Request(display,0,store().library(actor).revision(),0,++requestId,"PLAY",id,null,-1,-1,0,"");
        ScreenPlaylistRuntime.handle(player,BlockPos.ZERO,request.json());
        assertTrue(latest.success());assertEquals(image,ScreenRegistry.networks().snapshot(display).image());assertNull(ScreenRegistry.networks().snapshot(key).image());
    }
    @Test void transferInvalidatesSharedAndUnloadedLocalOverrideSessions() {
        UUID id=playlist();send("PLAY",id,null,-1,-1,0,"");
        UUID displayId=UUID.randomUUID();String override=ScreenNetworkService.display(displayId);
        ScreenRegistry.networks().createOwned(override,actor,null);ScreenRegistry.updateDisplay(displayId,"playlist-test",true,true);
        assertTrue(store().play(override,actor,id,0));
        ScreenPlaylistRuntime.invalidateAccess(key);
        assertFalse(store().snapshots().containsKey(key));assertFalse(store().snapshots().containsKey(override));
        assertFalse(store().command(key,"RESUME",0));assertFalse(store().command(override,"RESTART",0));
    }
    @Test void queuedSlidesDoNotBlankStaticContentBeforePlaybackStarts() {
        ScreenRegistry.reconcileContent(key,image);
        send("QUEUE_LAST",null,image,-1,-1,1000,"");assertTrue(latest.success());
        assertEquals(image,ScreenRegistry.networks().snapshot(key).image());
        ScreenPlaylistRuntime.shutdown();ScreenPlaylistRuntime.init(root);
        assertEquals(image,ScreenRegistry.networks().snapshot(key).image());assertEquals(1,store().snapshot(key).queue().size());
    }
    @Test void repeatedInitializationDoesNotDiscardDirtyOffsets() {
        UUID id=playlist();send("PLAY",id,null,-1,-1,0,"");store().tick(350);
        ScreenPlaylistRuntime.init(root);assertEquals(350,store().snapshot(key).offsetMillis());
    }
    @Test void requestWireRoundTripAndBounds() {
        var request=new PlaylistWire.Request(key,0,0,0,1,"GET",null,null,-1,-1,0,"");
        assertEquals(request,PlaylistWire.Request.parse(request.json()));
        assertThrows(RuntimeException.class,()->PlaylistWire.Request.parse("x".repeat(1025)));
        assertThrows(RuntimeException.class,()->PlaylistWire.Request.parse("null"));
        assertThrows(RuntimeException.class,()->PlaylistWire.Request.parse("{}"));
    }
}
