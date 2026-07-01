package com.nstut.simplyscreens.neoforge;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.BlockRegistries;
import com.nstut.simplyscreens.blocks.entities.BlockEntityRegistries;
import com.nstut.simplyscreens.creative_tabs.CreativeTabRegistries;
import com.nstut.simplyscreens.items.ItemRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod(SimplyScreens.MOD_ID)
public class SimplyScreensImpl {
    public SimplyScreensImpl(IEventBus modEventBus) {
        CreativeTabRegistries.CREATIVE_TABS.register();
        BlockRegistries.BLOCKS.register();
        BlockEntityRegistries.BLOCK_ENTITIES.register();
        ItemRegistries.ITEMS.register();

        modEventBus.addListener(this::onCommonSetup);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(SimplyScreens::initCommon);
    }
}
