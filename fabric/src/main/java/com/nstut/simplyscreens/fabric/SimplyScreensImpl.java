package com.nstut.simplyscreens.fabric;

import com.nstut.simplyscreens.SimplyScreens;
import net.fabricmc.api.ModInitializer;

public class SimplyScreensImpl implements ModInitializer {
    @Override
    public void onInitialize() {
        SimplyScreens.init();
    }
}