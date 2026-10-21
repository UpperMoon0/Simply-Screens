package com.nstut.simplyscreens.network;
import com.nstut.simplyscreens.*;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ScreenAccessPacketTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    RegistryFriendlyByteBuf buffer() { return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY); }
    @Test void preservesTargetRevisionAndActorFreeMutationData() {
        var buf=buffer();
        try {
            var packet=new ScreenAccessC2SPacket(BlockPos.ZERO,"network/station",42,"TRUST",UUID.randomUUID().toString(),1234);
            packet.write(buf); var decoded=new ScreenAccessC2SPacket(buf);
            assertEquals(packet.pos,decoded.pos); assertEquals(packet.key,decoded.key); assertEquals(42,decoded.revision);
            assertEquals(1234, decoded.requestId); assertEquals(packet.action,decoded.action); assertEquals(packet.value,decoded.value); assertFalse(buf.isReadable());
        } finally { buf.release(); }
    }
    @Test void rejectsOversizedAccessInputBeforeDispatch() {
        var buf=buffer();
        try {
            buf.writeBlockPos(BlockPos.ZERO); buf.writeUtf("x".repeat(129));
            assertThrows(DecoderException.class,()->new ScreenAccessC2SPacket(buf));
        } finally { buf.release(); }
    }
    @Test void maximumTrustSnapshotFitsBoundedResponseAndRetainsDisplayOverride() {
        var buf=buffer();
        try {
            Set<UUID> trusted=new HashSet<>(); for(int i=0;i<64;i++) trusted.add(UUID.randomUUID());
            var state=new ScreenNetworkService.State(UUID.randomUUID(),trusted,ScreenNetworkService.Policy.PUBLIC,
                    ScreenNetworkService.Policy.TRUSTED,UUID.randomUUID(),"STATIC",0,0,100);
            String json=new ScreenAccessSnapshot("network/station",state,true,true,true,Map.of(),9876).toJson();
            assertTrue(json.length()<8192);
            new ScreenAccessS2CPacket(BlockPos.ZERO,json).write(buf);
            var decoded=new ScreenAccessS2CPacket(buf); var result=ScreenAccessSnapshot.fromJson(decoded.json);
            assertEquals(state,result.state()); assertTrue(result.localOverride()); assertEquals(9876, result.requestId());
        } finally { buf.release(); }
    }
}
