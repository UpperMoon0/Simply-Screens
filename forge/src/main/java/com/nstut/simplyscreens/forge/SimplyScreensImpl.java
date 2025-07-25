package com.nstut.simplyscreens.forge;

import com.nstut.simplyscreens.SimplyScreens;
import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(SimplyScreens.MOD_ID)
public class SimplyScreensImpl {
    public SimplyScreensImpl() {
        EventBuses.registerModEventBus(SimplyScreens.MOD_ID, FMLJavaModLoadingContext.get().getModEventBus());
        SimplyScreens.init();
    }
}