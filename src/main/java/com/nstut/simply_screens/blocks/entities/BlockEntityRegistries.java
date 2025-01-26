package com.nstut.simply_screens.blocks.entities;

import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import com.nstut.simply_screens.SimplyScreens;
import com.nstut.simply_screens.blocks.BlockRegistries;

@SuppressWarnings("ConstantConditions")
public class BlockEntityRegistries {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, SimplyScreens.MOD_ID);

    public static final RegistryObject<BlockEntityType<ScreenBlockEntity>> SCREEN = BLOCK_ENTITIES.register("screen", () -> BlockEntityType.Builder.of(ScreenBlockEntity::new, BlockRegistries.SCREEN.get()).build(null));
}