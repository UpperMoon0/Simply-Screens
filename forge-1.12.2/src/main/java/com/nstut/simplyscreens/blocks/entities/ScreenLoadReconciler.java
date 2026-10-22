package com.nstut.simplyscreens.blocks.entities;
import com.nstut.simplyscreens.compat.LifecycleEvent;
import com.nstut.simplyscreens.compat.TickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.tileentity.TileEntity;
import java.util.LinkedHashMap;
import java.util.Map;
public final class ScreenLoadReconciler {
 private static final Map<WorldServer,Map<BlockPos,Integer>> PENDING=new LinkedHashMap<>();private static boolean initialized;
 public static void init() { if(initialized)return;initialized=true;LifecycleEvent.SERVER_BEFORE_START.register(s->clear());LifecycleEvent.SERVER_STOPPED.register(s->clear());TickEvent.SERVER_POST.register(ScreenLoadReconciler::process); }
 public static synchronized void enqueue(WorldServer world,BlockPos pos) { PENDING.computeIfAbsent(world,w->new LinkedHashMap<>()).put(pos.toImmutable(),0); }
 public static synchronized void clear() { PENDING.clear(); }
 private static synchronized void process(MinecraftServer server) {
  Map<WorldServer,Map<BlockPos,Integer>> batch=new LinkedHashMap<>(PENDING);PENDING.clear();
  for(Map.Entry<WorldServer,Map<BlockPos,Integer>> dimension:batch.entrySet()) {
   WorldServer world=dimension.getKey();if(world.getMinecraftServer()!=server||net.minecraftforge.common.DimensionManager.getWorld(world.provider.getDimension())!=world)continue;
   for(Map.Entry<BlockPos,Integer> item:dimension.getValue().entrySet()) {
    BlockPos pos=item.getKey();Chunk chunk=world.getChunkProvider().getLoadedChunk(pos.getX()>>4,pos.getZ()>>4);
    if(chunk==null) { if(item.getValue()<40)PENDING.computeIfAbsent(world,w->new LinkedHashMap<>()).put(pos,item.getValue()+1);continue; }
    TileEntity tile=chunk.getTileEntity(pos,Chunk.EnumCreateEntityType.CHECK);if(tile instanceof ScreenBlockEntity&&!tile.isInvalid())((ScreenBlockEntity)tile).reconcileAfterLoad();
   }
  }
 }
}
