package com.nstut.simplyscreens.blocks;

import com.nstut.simplyscreens.SimplyScreens;
import dev.architectury.platform.Platform;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import dev.architectury.utils.Env;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;

public class BlockRegistries {
    private static net.minecraft.world.level.block.state.BlockBehaviour.Properties properties() {
        return net.minecraft.world.level.block.state.BlockBehaviour.Properties.of()
            .mapColor(net.minecraft.world.level.material.MapColor.METAL).noOcclusion().strength(3.5F, 6.0F)
            .requiresCorrectToolForDrops().sound(net.minecraft.world.level.block.SoundType.METAL)
            .setId(net.minecraft.resources.ResourceKey.create(Registries.BLOCK,
                net.minecraft.resources.Identifier.fromNamespaceAndPath(SimplyScreens.MOD_ID, "screen")));
    }
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(SimplyScreens.MOD_ID, Registries.BLOCK);

    public static final RegistrySupplier<Block> SCREEN = BLOCKS.register("screen", () -> {
        if (Platform.getEnvironment() == Env.CLIENT) {
            return new ClientScreenBlock(properties());
        } else {
            return new ScreenBlock(properties());
        }
    });
}
