package com.nstut.simplyscreens.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.TickableBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Preserve the newer lifecycle hooks without reading the world at portable routing tokens. */
public abstract class LegacyBlockEntity extends BlockEntity implements TickableBlockEntity {
    private BlockState cachedState;
    protected LegacyBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type);
        this.worldPosition=pos;
        this.cachedState=state;
    }
    @Override public BlockState getBlockState() { return level == null ? cachedState : super.getBlockState(); }
    public void setBlockState(BlockState state) { cachedState=state; }
    @Override public void load(BlockState state, CompoundTag tag) { cachedState=state; load(tag); }
    public void load(CompoundTag tag) { super.load(cachedState,tag); }
    @Override public CompoundTag save(CompoundTag tag) { saveAdditional(tag); return tag; }
    protected void saveAdditional(CompoundTag tag) { super.save(tag); }
}
