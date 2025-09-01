package com.nstut.simplyscreens.blocks;

import com.nstut.simplyscreens.SimplyScreens;
import dev.architectury.platform.Platform;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.fabricmc.api.EnvType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;

public class BlockRegistries {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(SimplyScreens.MOD_ID, Registries.BLOCK);

    public static final RegistrySupplier<Block> SCREEN = BLOCKS.register("screen", () -> {
        if (Platform.getEnv() == EnvType.CLIENT) {
            return new ClientScreenBlock();
        } else {
            return new ScreenBlock();
        }
    });
}