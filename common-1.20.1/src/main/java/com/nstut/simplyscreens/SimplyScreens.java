package com.nstut.simplyscreens;

import com.nstut.simplyscreens.blocks.entities.ScreenLoadReconciler;
import com.nstut.simplyscreens.network.PacketRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimplyScreens {
    public static final String MOD_ID = "simply_screens";
    public static final Logger LOGGER = LoggerFactory.getLogger("Simply Screens");

    public static void initCommon() {
        LOGGER.info("Initializing Simply Screens Common");

        // WebP support will be handled by the library automatically
        Config.setModId(MOD_ID);
        Config.load();
        PacketRegistries.register();

        ScreenLoadReconciler.init();
        ServerTickScheduler.init();
    }

    public static void initializeScreens(java.nio.file.Path worldSavePath) {
        LOGGER.info("Initializing Screen Registry");
        ScreenRegistry.init(worldSavePath);
    }
}
