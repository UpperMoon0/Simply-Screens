package com.nstut.simplyscreens.compat;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.util.ResourceLocation;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
/** Native Forge wire transport, with separate allowed IDs per direction. */
public final class NetworkManager {
 public enum Side { CLIENT, SERVER }
 public interface PacketContext { EntityPlayer getPlayer();void queue(Runnable action); }
 private static final SimpleNetworkWrapper CHANNEL=NetworkRegistry.INSTANCE.newSimpleChannel("simply_screens");
 private static final Map<ResourceLocation,BiConsumer<LegacyPacketBuffer,PacketContext>> C2S=new ConcurrentHashMap<>(),S2C=new ConcurrentHashMap<>();
 public static Side c2s() { return Side.SERVER; } public static Side s2c() { return Side.CLIENT; }
 public static void init() {
  CHANNEL.registerMessage(ServerHandler.class,ServerboundEnvelope.class,0,net.minecraftforge.fml.relauncher.Side.SERVER);
  CHANNEL.registerMessage(ClientHandler.class,ClientboundEnvelope.class,1,net.minecraftforge.fml.relauncher.Side.CLIENT);
 }
 public static void registerReceiver(Side side,ResourceLocation id,BiConsumer<LegacyPacketBuffer,PacketContext> receiver) {
  if((side==Side.SERVER?C2S:S2C).putIfAbsent(id,receiver)!=null)throw new IllegalStateException("Duplicate packet "+id);
 }
 public static void sendToPlayer(EntityPlayerMP player,ResourceLocation id,LegacyPacketBuffer buffer) { CHANNEL.sendTo(new ClientboundEnvelope(id,buffer),player); }
 public static void sendToServer(ResourceLocation id,LegacyPacketBuffer buffer) { CHANNEL.sendToServer(new ServerboundEnvelope(id,buffer)); }
 private static void deliver(Envelope message,PacketContext context,Map<ResourceLocation,BiConsumer<LegacyPacketBuffer,PacketContext>> allowed) {
  BiConsumer<LegacyPacketBuffer,PacketContext> handler=allowed.get(message.id);
  if(handler==null)return;
  LegacyPacketBuffer data=new LegacyPacketBuffer(Unpooled.wrappedBuffer(message.payload));
  try { handler.accept(data,context); }
  catch(RuntimeException invalid) { com.nstut.simplyscreens.SimplyScreens.LOGGER.debug("Rejected malformed screen packet {}",message.id,invalid); }
  finally { data.release(); }
 }
 public static class Envelope implements IMessage {
  private ResourceLocation id;private byte[] payload;
  public Envelope() { }
  Envelope(ResourceLocation id,LegacyPacketBuffer bytes) { this.id=id;try { int size=bytes.readableBytes();if(size>1024*1024)throw new IllegalArgumentException("Oversized screen packet");payload=new byte[size];bytes.readBytes(payload); }finally { bytes.release(); } }
  public void fromBytes(ByteBuf bytes) { LegacyPacketBuffer data=new LegacyPacketBuffer(bytes);id=new ResourceLocation(data.readUtf(128));payload=data.readByteArray(1024*1024);if(data.isReadable())throw new IllegalArgumentException("Trailing envelope bytes"); }
  public void toBytes(ByteBuf bytes) { LegacyPacketBuffer data=new LegacyPacketBuffer(bytes);data.writeUtf(id.toString(),128);data.writeByteArray(payload); }
 }
 public static final class ServerboundEnvelope extends Envelope {
  public ServerboundEnvelope() { }ServerboundEnvelope(ResourceLocation id,LegacyPacketBuffer bytes) { super(id,bytes); }
 }
 public static final class ClientboundEnvelope extends Envelope {
  public ClientboundEnvelope() { }ClientboundEnvelope(ResourceLocation id,LegacyPacketBuffer bytes) { super(id,bytes); }
 }
 public static final class ServerHandler implements IMessageHandler<ServerboundEnvelope,IMessage> {
  public IMessage onMessage(ServerboundEnvelope message,MessageContext context) {
   EntityPlayerMP player=context.getServerHandler().player;
   deliver(message,new PacketContext() { public EntityPlayer getPlayer() { return player; }public void queue(Runnable action) { player.getServerWorld().addScheduledTask(action); } },C2S);return null;
  }
 }
 public static void receiveClient(Envelope message,PacketContext context) { deliver(message,context,S2C); }
 public static final class ClientHandler implements IMessageHandler<ClientboundEnvelope,IMessage> {
  public IMessage onMessage(ClientboundEnvelope message,MessageContext context) { com.nstut.simplyscreens.forge.SimplyScreensForge.proxy.receive(message);return null; }
 }
}
