package com.nstut.simply_screens.blocks;

import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import com.nstut.simply_screens.SimplyScreens;

public class BlockRegistries {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, SimplyScreens.MOD_ID);

    public static final RegistryObject<Block> SCREEN = BLOCKS.register("screen", ScreenBlock::new);
}
