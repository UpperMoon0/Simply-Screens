package com.nstut.simplyscreens.fabric;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.BlockRegistries;
import com.nstut.simplyscreens.fabric.config.FabricConfig;
import com.nstut.simplyscreens.blocks.entities.BlockEntityRegistries;
import com.nstut.simplyscreens.creative_tabs.CreativeTabRegistries;
import com.nstut.simplyscreens.items.ItemRegistries;
import net.fabricmc.api.ModInitializer;

public class SimplyScreensImpl implements ModInitializer {
    @Override
    public void onInitialize() {
        FabricConfig.init();

        CreativeTabRegistries.CREATIVE_TABS.register();
        BlockRegistries.BLOCKS.register();
        BlockEntityRegistries.BLOCK_ENTITIES.register();
        ItemRegistries.ITEMS.register();

        SimplyScreens.initCommon();
    }
}