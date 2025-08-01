package com.nstut.simplyscreens.fabric.config;

import com.nstut.simplyscreens.Config;
import com.nstut.simplyscreens.SimplyScreens;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Properties;

public class FabricConfig {
    private static final File CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve(SimplyScreens.MOD_ID + ".properties").toFile();

    public static void init() {
        if (!CONFIG_FILE.exists()) {
            writeConfig();
        } else {
            readConfig();
        }
    }

    private static void readConfig() {
        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            Properties props = new Properties();
            props.load(reader);

            try {
                int viewDistance = Integer.parseInt(props.getProperty("viewDistance", String.valueOf(Config.VIEW_DISTANCE)));
                Config.VIEW_DISTANCE = viewDistance;
            } catch (NumberFormatException e) {
                SimplyScreens.LOGGER.error("Failed to parse view distance from config", e);
            }

            try {
                int screenTickRate = Integer.parseInt(props.getProperty("screenTickRate", String.valueOf(Config.SCREEN_TICK_RATE)));
                Config.SCREEN_TICK_RATE = screenTickRate;
            } catch (NumberFormatException e) {
                SimplyScreens.LOGGER.error("Failed to parse screen tick rate from config", e);
            }

            Config.DISABLE_UPLOAD = Boolean.parseBoolean(props.getProperty("disableUpload", String.valueOf(Config.DISABLE_UPLOAD)));

            try {
                int size = Integer.parseInt(props.getProperty("maxUploadSize", String.valueOf(Config.MAX_UPLOAD_SIZE)));
                Config.MAX_UPLOAD_SIZE = Math.max(Config.MIN_UPLOAD_SIZE, Math.min(Config.MAX_UPLOAD_SIZE_LIMIT, size));
            } catch (NumberFormatException e) {
                SimplyScreens.LOGGER.error("Failed to parse max upload size from config", e);
            }

        } catch (IOException e) {
            SimplyScreens.LOGGER.error("Failed to read config file", e);
            writeConfig();
        }
    }

    private static void writeConfig() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            Properties props = new Properties();
            props.setProperty("viewDistance", String.valueOf(Config.VIEW_DISTANCE));
            props.setProperty("screenTickRate", String.valueOf(Config.SCREEN_TICK_RATE));
            props.setProperty("disableUpload", String.valueOf(Config.DISABLE_UPLOAD));
            props.setProperty("maxUploadSize", String.valueOf(Config.MAX_UPLOAD_SIZE));

            props.store(writer, "Simply Screens Configuration");
        } catch (IOException e) {
            SimplyScreens.LOGGER.error("Failed to write config file", e);
        }
    }
}