package com.nstut.simplyscreens.forge;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.BlockRegistries;
import com.nstut.simplyscreens.blocks.entities.BlockEntityRegistries;
import com.nstut.simplyscreens.creative_tabs.CreativeTabRegistries;
import com.nstut.simplyscreens.items.ItemRegistries;
import com.nstut.simplyscreens.forge.config.ForgeConfig;
import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(SimplyScreens.MOD_ID)
public class SimplyScreensImpl {
    public SimplyScreensImpl() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        EventBuses.registerModEventBus(SimplyScreens.MOD_ID, modEventBus);

        CreativeTabRegistries.CREATIVE_TABS.register();
        BlockRegistries.BLOCKS.register();
        BlockEntityRegistries.BLOCK_ENTITIES.register();
        ItemRegistries.ITEMS.register();

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ForgeConfig.SPEC);

        modEventBus.addListener(this::onCommonSetup);
    }

    public void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(SimplyScreens::initCommon);
    }
}