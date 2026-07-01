package com.nstut.simplyscreens.blocks;

import com.nstut.simplyscreens.SimplyScreens;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class BlockRegistries {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(SimplyScreens.MOD_ID);
    public static final DeferredBlock<Block> SCREEN = BLOCKS.registerBlock("screen",
            properties -> Platform.getEnvironment() == Env.CLIENT
                    ? new ClientScreenBlock(properties) : new ScreenBlock(properties),
            properties -> properties.mapColor(MapColor.METAL).noOcclusion().strength(3.5F, 6.0F)
                    .requiresCorrectToolForDrops().sound(SoundType.METAL));

    private BlockRegistries() {
    }
}
