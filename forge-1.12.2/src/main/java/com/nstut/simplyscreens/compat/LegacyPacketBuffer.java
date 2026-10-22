package com.nstut.simplyscreens.compat;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.PacketBuffer;
import java.util.UUID;
/** Bounds match the modern wire format; string writes also enforce the character limit. */
public final class LegacyPacketBuffer extends PacketBuffer {
 public LegacyPacketBuffer(ByteBuf buffer) { super(buffer); }
 public String readUtf(int max) { int size=readVarInt();if(max<0 || size<0 || size>(long)max*3 || size>readableBytes())throw new io.netty.handler.codec.DecoderException("Invalid UTF-8 wire length");String value=toString(readerIndex(),size,java.nio.charset.StandardCharsets.UTF_8);skipBytes(size);if(value.length()>max)throw new io.netty.handler.codec.DecoderException("String exceeds wire limit");return value; }
 public String readUtf() { return readUtf(32767); }
 public LegacyPacketBuffer writeUtf(String value,int max) { if(max<0 || value.length()>max)throw new IllegalArgumentException("String exceeds wire limit");byte[] utf8=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);if(utf8.length>(long)max*3)throw new IllegalArgumentException("UTF-8 exceeds wire limit");writeVarInt(utf8.length);writeBytes(utf8);return this; }
 public LegacyPacketBuffer writeUtf(String value) { return writeUtf(value,32767); }
 public UUID readUUID() { return readUniqueId(); }
 public LegacyPacketBuffer writeUUID(UUID value) { writeUniqueId(value);return this; }
 public int readVarInt() { return super.readVarInt(); }
 public LegacyPacketBuffer writeVarInt(int value) { super.writeVarInt(value);return this; }
}
