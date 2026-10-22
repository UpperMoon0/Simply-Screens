package com.nstut.simplyscreens.compat;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.util.ResourceLocation;
import org.junit.Test;
import static org.junit.Assert.*;
/** Exercises actual native IMessage encoding without initializing a game/channel. */
public class NativeEnvelopeTest {
 @Test public void directionalMessageTypesAreDistinct() { assertNotSame(NetworkManager.ServerboundEnvelope.class,NetworkManager.ClientboundEnvelope.class); }
 @Test public void nativeEnvelopeRoundTripReleasesSourceBuffer() {
  LegacyPacketBuffer payload=new LegacyPacketBuffer(Unpooled.buffer());payload.writeUtf("hello",5);
  NetworkManager.ServerboundEnvelope sent=new NetworkManager.ServerboundEnvelope(new ResourceLocation("simply_screens","test"),payload);
  assertEquals(0,payload.refCnt());ByteBuf wire=Unpooled.buffer(),copy=Unpooled.buffer();
  try { sent.toBytes(wire);byte[] expected=new byte[wire.readableBytes()];wire.getBytes(0,expected);
   NetworkManager.ServerboundEnvelope received=new NetworkManager.ServerboundEnvelope();received.fromBytes(wire);received.toBytes(copy);
   byte[] actual=new byte[copy.readableBytes()];copy.readBytes(actual);assertArrayEquals(expected,actual);
  }finally { wire.release();copy.release(); }
 }
 @Test public void oversizedOutboundPayloadRejectedAndReleased() {
  LegacyPacketBuffer payload=new LegacyPacketBuffer(Unpooled.buffer());payload.writeZero(1024*1024+1);
  try { new NetworkManager.ServerboundEnvelope(new ResourceLocation("simply_screens","test"),payload);fail("Oversized payload accepted"); }
  catch(IllegalArgumentException expected) { assertEquals(0,payload.refCnt()); }
 }
 @Test(expected=IllegalArgumentException.class) public void trailingEnvelopeBytesRejected() {
  LegacyPacketBuffer payload=new LegacyPacketBuffer(Unpooled.buffer());NetworkManager.ServerboundEnvelope packet=new NetworkManager.ServerboundEnvelope(new ResourceLocation("simply_screens","test"),payload);
  ByteBuf wire=Unpooled.buffer();try { packet.toBytes(wire);wire.writeByte(1);new NetworkManager.ServerboundEnvelope().fromBytes(wire); }finally { wire.release(); }
 }
}
