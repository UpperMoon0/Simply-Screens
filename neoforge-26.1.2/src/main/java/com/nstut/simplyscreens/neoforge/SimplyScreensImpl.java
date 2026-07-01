package com.nstut.simplyscreens.neoforge;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.BlockRegistries;
import com.nstut.simplyscreens.blocks.entities.BlockEntityRegistries;
import com.nstut.simplyscreens.creative_tabs.CreativeTabRegistries;
import com.nstut.simplyscreens.items.ItemRegistries;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

@Mod(SimplyScreens.MOD_ID)
public final class SimplyScreensImpl {
    public SimplyScreensImpl(IEventBus modBus, ModContainer container) {
        CreativeTabRegistries.CREATIVE_TABS.register(modBus);
        BlockRegistries.BLOCKS.register(modBus);
        BlockEntityRegistries.BLOCK_ENTITIES.register(modBus);
        ItemRegistries.ITEMS.register(modBus);
        modBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.register(this);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(SimplyScreens::initCommon);
    }

    @SubscribeEvent
    public void serverStarting(ServerStartingEvent event) {
        SimplyScreens.initializeScreens(event.getServer().getWorldPath(LevelResource.ROOT));
    }
}
