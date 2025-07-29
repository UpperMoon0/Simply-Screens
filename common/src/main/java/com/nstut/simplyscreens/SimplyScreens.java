package com.nstut.simplyscreens;

import com.nstut.simplyscreens.network.PacketRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class SimplyScreens {
    public static final String MOD_ID = "simplyscreens";
    public static final Logger LOGGER = LoggerFactory.getLogger("Simply Screens");

    public static void initCommon() {
        LOGGER.info("Initializing Simply Screens Common");

        // WebP support is loaded automatically via ServiceLoader

        Config.load();
        PacketRegistries.register();
    }
}
