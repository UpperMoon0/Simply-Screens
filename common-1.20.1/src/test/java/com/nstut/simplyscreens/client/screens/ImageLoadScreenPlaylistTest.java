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
}
