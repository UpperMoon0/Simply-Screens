package com.nstut.simplyscreens.network;
import com.nstut.simplyscreens.playlist.PlaylistWire;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ScreenPlaylistPacketTest {
    FriendlyByteBuf buffer() {return new FriendlyByteBuf(Unpooled.buffer());}
    @Test void requestRoundTripIsBoundedAndActorFree() {
        var b=buffer();try {
            var request=new PlaylistWire.Request("network/test",4,7,8,99,"PLAY",UUID.randomUUID(),null,-1,-1,0,"");
            new ScreenPlaylistC2SPacket(BlockPos.ZERO,request.json()).write(b);
            var decoded=new ScreenPlaylistC2SPacket(b);assertEquals(BlockPos.ZERO,decoded.pos);
            assertEquals(request,PlaylistWire.Request.parse(decoded.json));assertFalse(b.isReadable());
        } finally {b.release();}
    }
    @Test void rejectsOversizeBeforeServerDispatch() {
        var b=buffer();try {b.writeBlockPos(BlockPos.ZERO);b.writeUtf("x".repeat(PlaylistWire.REQUEST_LIMIT+1));
            assertThrows(DecoderException.class,()->new ScreenPlaylistC2SPacket(b));
        } finally {b.release();}
    }
    @Test void maximumEditorFitsResponseAndPreservesDurations() {
        var b=buffer();try {
            UUID id=UUID.randomUUID();var lists=new ArrayList<PlaylistWire.Summary>();var slides=new ArrayList<PlaylistWire.Slide>();
            for(int i=0;i<64;i++)lists.add(new PlaylistWire.Summary(UUID.randomUUID(),"名".repeat(64),256));
            for(int i=0;i<256;i++)slides.add(new PlaylistWire.Slide(UUID.randomUUID(),86400000));
            var view=new PlaylistWire.View("network/test",4,7,8,99,true,true,id,lists,slides,"PLAYING",id,255,300,"LIST",true,slides.subList(0,64),List.of(UUID.randomUUID(),UUID.randomUUID()));
            new ScreenPlaylistS2CPacket(BlockPos.ZERO,view.json()).write(b);
            var decoded=new ScreenPlaylistS2CPacket(b);assertEquals(view,PlaylistWire.View.parse(decoded.json));assertFalse(b.isReadable());
        } finally {b.release();}
    }
}
