package com.nstut.simplyscreens;

import com.nstut.simplyscreens.blocks.BlockRegistries;
import com.nstut.simplyscreens.blocks.entities.BlockEntityRegistries;
import com.nstut.simplyscreens.creative_tabs.CreativeTabRegistries;
import com.nstut.simplyscreens.items.ItemRegistries;
import com.nstut.simplyscreens.network.PacketRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimplyScreens {
    public static final String MOD_ID = "simplyscreens";
    public static final Logger LOGGER = LoggerFactory.getLogger("Simply Screens");

    public static void init() {
        LOGGER.info("Initializing Simply Screens");

        Config.load();
        CreativeTabRegistries.init();
        BlockRegistries.init();
        BlockEntityRegistries.init();
        ItemRegistries.init();
        PacketRegistries.register();
    }
}
