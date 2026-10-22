package com.nstut.simplyscreens.compat;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.*;
public class LegacyPacketBufferTest {
 @Test public void modernWireRoundTrip() { LegacyPacketBuffer bytes=new LegacyPacketBuffer(Unpooled.buffer());try { UUID id=UUID.randomUUID();bytes.writeUtf("test",8);bytes.writeUUID(id);bytes.writeVarInt(301);assertEquals("test",bytes.readUtf(8));assertEquals(id,bytes.readUUID());assertEquals(301,bytes.readVarInt());assertFalse(bytes.isReadable()); }finally { bytes.release(); } }
 @Test public void unicodeUsesCharacterLimitNotOldByteCap() { LegacyPacketBuffer bytes=new LegacyPacketBuffer(Unpooled.buffer());try { char[] chars=new char[16000];java.util.Arrays.fill(chars,'界');String value=new String(chars);bytes.writeUtf(value,16000);assertEquals(value,bytes.readUtf(16000)); }finally { bytes.release(); } }
 @Test(expected=io.netty.handler.codec.DecoderException.class) public void truncatedUtf8RejectedBeforeAllocation() { LegacyPacketBuffer bytes=new LegacyPacketBuffer(Unpooled.buffer());try { bytes.writeVarInt(10);bytes.writeByte(65);bytes.readUtf(10); }finally { bytes.release(); } }
 @Test(expected=io.netty.handler.codec.DecoderException.class) public void negativeUtf8LengthRejected() { LegacyPacketBuffer bytes=new LegacyPacketBuffer(Unpooled.buffer());try { bytes.writeVarInt(-1);bytes.readUtf(10); }finally { bytes.release(); } }
 @Test(expected=IllegalArgumentException.class) public void oversizedStringRejectedAtWrite() { LegacyPacketBuffer bytes=new LegacyPacketBuffer(Unpooled.buffer());try { bytes.writeUtf("too long",3); }finally { bytes.release(); } }
 @Test(expected=io.netty.handler.codec.DecoderException.class) public void oversizedStringRejectedAtRead() { LegacyPacketBuffer bytes=new LegacyPacketBuffer(Unpooled.buffer());try { bytes.writeUtf("too long",10);bytes.readUtf(3); }finally { bytes.release(); } }
}
