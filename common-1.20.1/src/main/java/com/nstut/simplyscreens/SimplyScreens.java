package com.nstut.simplyscreens;

import com.nstut.simplyscreens.network.PacketRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimplyScreens {
    public static final String MOD_ID = "simplyscreens";
    public static final Logger LOGGER = LoggerFactory.getLogger("Simply Screens");

    public static void initCommon() {
        LOGGER.info("Initializing Simply Screens Common");

        // WebP support will be handled by the library automatically
        Config.setModId(MOD_ID);
        Config.load();
        PacketRegistries.register();
    }

    public static void initializeScreens() {
        LOGGER.info("Initializing Screen Registry");
        // ScreenRegistry will be lazily initialized when first accessed
    }
}
