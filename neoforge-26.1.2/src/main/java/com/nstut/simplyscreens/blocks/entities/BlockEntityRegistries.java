package com.nstut.simplyscreens.blocks.entities;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.BlockRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class BlockEntityRegistries {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, SimplyScreens.MOD_ID);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ScreenBlockEntity>> SCREEN =
            BLOCK_ENTITIES.register("screen", () -> new BlockEntityType<>(ScreenBlockEntity::new, BlockRegistries.SCREEN.get()));

    private BlockEntityRegistries() {
    }
}
