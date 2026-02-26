package com.nstut.simplyscreens;

import com.nstut.simplyscreens.network.PacketRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class SimplyScreens {
    public static final String MOD_ID = "simply_screens";
    public static final Logger LOGGER = LoggerFactory.getLogger("Simply Screens");

    public static void initCommon() {
        LOGGER.info("Initializing Simply Screens Common");

        // WebP support will be handled by the library automatically
        Config.load();
        PacketRegistries.register();
    }

    /**
     * Initializes the ScreenRegistry with the world save path.
     * This should be called when a world/server is loaded.
     *
     * @param worldSavePath The path to the world save directory
     */
    public static void initializeScreens(Path worldSavePath) {
        ScreenRegistry.init(worldSavePath);
    }
}
