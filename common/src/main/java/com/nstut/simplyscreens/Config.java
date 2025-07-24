package com.nstut.simplyscreens;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public class Config {
    private static final Path CONFIG_PATH = Paths.get("config", SimplyScreens.MOD_ID + ".properties");
    private static final Properties properties = new Properties();

    public static int VIEW_DISTANCE;
    public static int SCREEN_TICK_RATE;

    public static void load() {
        try {
            if (Files.notExists(CONFIG_PATH)) {
                createDefaultConfig();
            }
            try (FileInputStream stream = new FileInputStream(CONFIG_PATH.toFile())) {
                properties.load(stream);
            }
        } catch (IOException e) {
            SimplyScreens.LOGGER.error("Failed to load config file", e);
        }

        VIEW_DISTANCE = getInt("viewDistance", 64);
        SCREEN_TICK_RATE = getInt("screenTickRate", 100);
    }

    private static void createDefaultConfig() throws IOException {
        Files.createDirectories(CONFIG_PATH.getParent());
        properties.setProperty("viewDistance", "64");
        properties.setProperty("screenTickRate", "100");
        try (FileOutputStream stream = new FileOutputStream(CONFIG_PATH.toFile())) {
            properties.store(stream, "Simply Screens Config");
        }
    }

    private static int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(properties.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
