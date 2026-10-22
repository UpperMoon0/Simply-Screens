package com.nstut.simplyscreens.compat;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.World;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.util.ITickable;
/** Native serialization/ticking bridge. TileEntity owns the position and world. */
public abstract class LegacyBlockEntity extends TileEntity implements ITickable {
    public IBlockState getBlockState() { return world.getBlockState(pos); }
    @Override public double getMaxRenderDistanceSquared() { return Double.MAX_VALUE; }
    public World getLevel() { return world; }
    public void saveAdditional(NBTTagCompound data) { }
    public void load(NBTTagCompound data) { }
    @Override public NBTTagCompound writeToNBT(NBTTagCompound data) { super.writeToNBT(data);saveAdditional(data);return data; }
    @Override public void readFromNBT(NBTTagCompound data) { super.readFromNBT(data);load(data); }
    @Override public void handleUpdateTag(NBTTagCompound data) { load(data); }
    @Override public void onDataPacket(NetworkManager manager,SPacketUpdateTileEntity packet) { load(packet.getNbtCompound()); }
    public void setLevel(World world) { super.setWorld(world); }
    @Override public void setWorld(World world) { setLevel(world); }
    public void setRemoved() { super.invalidate(); }
    @Override public void invalidate() { setRemoved(); }
    @Override public void onChunkUnload() { setRemoved();super.onChunkUnload(); }
    public abstract void tick();
    @Override public final void update() { tick(); }
}
