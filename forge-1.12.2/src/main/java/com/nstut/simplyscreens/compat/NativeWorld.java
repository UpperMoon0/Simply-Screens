package com.nstut.simplyscreens.compat;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
public final class NativeWorld {
    private NativeWorld() { }
    public static Path root(MinecraftServer server) { return server.getWorld(0).getSaveHandler().getWorldDirectory().toPath(); }
    public static String dimension(World world) {
        int id=world.provider.getDimension();
        return id==0?"minecraft:overworld":id==-1?"minecraft:the_nether":id==1?"minecraft:the_end":"forge:dimension_"+id;
    }
    public static List<EntityPlayerMP> tracking(WorldServer world,BlockPos position) {
        List<EntityPlayerMP> result=new ArrayList<>();
        for(EntityPlayerMP player:world.getMinecraftServer().getPlayerList().getPlayers())
            if(player.world==world && world.getPlayerChunkMap().isPlayerWatchingChunk(player,position.getX()>>4,position.getZ()>>4))result.add(player);
        return result;
    }
}
