package com.nstut.simplyscreens.client.screens;
import com.nstut.simplyscreens.network.*;
import com.nstut.simplyscreens.playlist.PlaylistWire;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImageLoadScreenPlaylistTest {
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path preferences;
    @org.junit.jupiter.api.BeforeAll static void isolatePreferences() {
        System.setProperty("simplyscreens.ui.config.dir", preferences.toString());
    }
    @org.junit.jupiter.api.AfterAll static void restorePreferences() {
        System.clearProperty("simplyscreens.ui.config.dir");
    }

    void action(ImageLoadScreen screen,String action) throws Exception {
        var method=ImageLoadScreen.class.getDeclaredMethod("playlistAction",String.class);method.setAccessible(true);method.invoke(screen,action);
    }
    Object field(ImageLoadScreen screen,String name) throws Exception {
        var field=ImageLoadScreen.class.getDeclaredField(name);field.setAccessible(true);return field.get(screen);
    }
    PlaylistWire.View view(long request,boolean control,UUID selected) {
        return new PlaylistWire.View("network/test",7,4,3,request,true,control,selected,
                selected==null?List.of():List.of(new PlaylistWire.Summary(selected,"Slides",0)),List.of(),"STOPPED",null,-1,0,"NONE",false,List.of(),List.of());
    }
    @Test void onlyLatestReadResponseCanChangeEditorSelection() throws Exception {
        var screen=new ImageLoadScreen(BlockPos.ZERO); UUID older=UUID.randomUUID(),latest=UUID.randomUUID();
        try(var packets=mockStatic(PacketRegistries.class)) {
            action(screen,"GET");long first=(long)field(screen,"pendingPlaylistRequest");
            action(screen,"GET");long second=(long)field(screen,"pendingPlaylistRequest");
            screen.updatePlaylist(BlockPos.ZERO,view(first,true,older).json());assertNull(field(screen,"selectedPlaylist"));
            screen.updatePlaylist(BlockPos.ZERO,view(second,true,latest).json());assertEquals(latest,field(screen,"selectedPlaylist"));
            screen.updatePlaylist(BlockPos.ZERO,view(first,true,older).json());assertEquals(latest,field(screen,"selectedPlaylist"));
        }
    }
    @Test void mutationIsSingleFlightAndUsesAuthoritativeRevisions() throws Exception {
        var screen=new ImageLoadScreen(BlockPos.ZERO);
        try(var packets=mockStatic(PacketRegistries.class)) {
            action(screen,"GET");long read=(long)field(screen,"pendingPlaylistRequest");
            screen.updatePlaylist(BlockPos.ZERO,view(read,true,null).json());packets.reset();
            action(screen,"RESUME");action(screen,"RESUME");action(screen,"GET");
            var capture=ArgumentCaptor.forClass(ScreenPlaylistC2SPacket.class);
            packets.verify(()->PacketRegistries.sendToServer(capture.capture()));packets.verifyNoMoreInteractions();
            var request=PlaylistWire.Request.parse(capture.getValue().json);
            assertEquals(7,request.networkRevision());assertEquals(4,request.libraryRevision());assertEquals(3,request.playbackRevision());
            assertTrue((boolean)field(screen,"playlistMutationPending"));
            screen.updatePlaylist(new BlockPos(1,0,0),view(request.requestId(),true,null).json());
            assertTrue((boolean)field(screen,"playlistMutationPending"));
            screen.updatePlaylist(BlockPos.ZERO,view(request.requestId(),true,null).json());
            assertFalse((boolean)field(screen,"playlistMutationPending"));
        }
    }
    @Test void viewerCannotEmitTransportOrLibraryMutations() throws Exception {
        var screen=new ImageLoadScreen(BlockPos.ZERO);
        try(var packets=mockStatic(PacketRegistries.class)) {
            action(screen,"GET");long read=(long)field(screen,"pendingPlaylistRequest");
            screen.updatePlaylist(BlockPos.ZERO,view(read,false,null).json());packets.reset();
            action(screen,"PLAY");action(screen,"CREATE");action(screen,"STOP");packets.verifyNoInteractions();
        }
    }
    @Test void refreshPreservesMutationUntilTimeoutThenRecovers() throws Exception {
        var screen=new ImageLoadScreen(BlockPos.ZERO);
        try(var packets=mockStatic(PacketRegistries.class)) {
            action(screen,"GET");long read=(long)field(screen,"pendingPlaylistRequest");
            screen.updatePlaylist(BlockPos.ZERO,view(read,true,null).json());
            action(screen,"RESUME");long write=(long)field(screen,"pendingPlaylistRequest");
            var dirty=ImageLoadScreen.class.getDeclaredField("playlistDirty");dirty.setAccessible(true);dirty.set(screen,true);
            packets.reset();
            for(int i=0;i<79;i++) screen.tick();
            packets.verifyNoInteractions();assertTrue((boolean)field(screen,"playlistMutationPending"));
            assertEquals(write,field(screen,"pendingPlaylistRequest"));
            screen.tick();
            assertFalse((boolean)field(screen,"playlistMutationPending"));
            long recovered=(long)field(screen,"pendingPlaylistRequest");assertTrue(recovered>write);
            screen.updatePlaylist(BlockPos.ZERO,view(write,true,UUID.randomUUID()).json());
            assertNull(field(screen,"selectedPlaylist"));assertEquals(recovered,field(screen,"pendingPlaylistRequest"));
            var capture=ArgumentCaptor.forClass(ScreenPlaylistC2SPacket.class);
            packets.verify(()->PacketRegistries.sendToServer(capture.capture()));packets.verifyNoMoreInteractions();
            assertEquals("GET",PlaylistWire.Request.parse(capture.getValue().json).action());
        }
    }

    @Test void playlistEditorDoesNotPauseAnIntegratedServer() {
        assertFalse(new ImageLoadScreen(BlockPos.ZERO).isPauseScreen());
    }
    @Test void compactPlayerStartsTheSelectedSavedPlaylistInsteadOfResumingAnEmptySession() throws Exception {
        var screen = new ImageLoadScreen(BlockPos.ZERO); UUID list = UUID.randomUUID(), image = UUID.randomUUID();
        try (var packets = mockStatic(PacketRegistries.class)) {
            action(screen, "GET"); long request = (long)field(screen, "pendingPlaylistRequest");
            var state = new PlaylistWire.View("network/test",7,4,3,request,true,true,list,
                    List.of(new PlaylistWire.Summary(list,"Slides",1)),List.of(new PlaylistWire.Slide(image,10000)),
                    "STOPPED",null,-1,0,"NONE",false,List.of(),List.of());
            screen.updatePlaylist(BlockPos.ZERO,state.json()); packets.reset();
            screen.togglePlaylistPlayback();
            var capture = ArgumentCaptor.forClass(ScreenPlaylistC2SPacket.class);
            packets.verify(() -> PacketRegistries.sendToServer(capture.capture()));
            var start = PlaylistWire.Request.parse(capture.getValue().json);
            assertEquals("PLAY",start.action()); assertEquals(list,start.playlist());
        }
    }
    @Test void slowReadIsNotContinuouslySupersededByPolling() throws Exception {
        var screen = new ImageLoadScreen(BlockPos.ZERO);
        try (var packets = mockStatic(PacketRegistries.class)) {
            action(screen,"GET"); long request=(long)field(screen,"pendingPlaylistRequest"); packets.reset();
            for (int i=0;i<60;i++) screen.tick();
            packets.verifyNoInteractions(); assertEquals(request,field(screen,"pendingPlaylistRequest"));
            screen.updatePlaylist(BlockPos.ZERO,view(request,true,null).json());
            assertNotNull(((com.nstut.openui.state.Signal<?>)field(screen,"playlistView")).get());
        }
    }
    @Test void creatingAPlaylistFromAnImageAddsTheImageAfterTheCreateReply() throws Exception {
        var screen = new ImageLoadScreen(BlockPos.ZERO); UUID image=UUID.randomUUID(),list=UUID.randomUUID();
        try (var packets = mockStatic(PacketRegistries.class)) {
            action(screen,"GET"); screen.updatePlaylist(BlockPos.ZERO,view((long)field(screen,"pendingPlaylistRequest"),true,null).json());
            var pending=ImageLoadScreen.class.getDeclaredField("pendingCreateImage");pending.setAccessible(true);pending.set(screen,image);
            action(screen,"CREATE");long create=(long)field(screen,"pendingPlaylistRequest");packets.reset();
            screen.updatePlaylist(BlockPos.ZERO,view(create,true,list).json());
            var capture=ArgumentCaptor.forClass(ScreenPlaylistC2SPacket.class);
            packets.verify(()->PacketRegistries.sendToServer(capture.capture()));
            var add=PlaylistWire.Request.parse(capture.getValue().json);
            assertEquals("ADD",add.action());assertEquals(list,add.playlist());assertEquals(image,add.image());
            assertNull(field(screen,"pendingCreateImage"));
        }
    }

}
