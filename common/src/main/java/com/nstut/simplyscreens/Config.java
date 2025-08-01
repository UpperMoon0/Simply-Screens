package com.nstut.simplyscreens;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public class Config {
    public static int VIEW_DISTANCE = 64;
    public static int SCREEN_TICK_RATE = 100;
    public static boolean DISABLE_UPLOAD = false;
    public static int MAX_UPLOAD_SIZE = 5 * 1024 * 1024; // 5MB

    public static final int MIN_UPLOAD_SIZE = 1024; // 1KB
    public static final int MAX_UPLOAD_SIZE_LIMIT = 100 * 1024 * 1024; // 100MB

    private static final Path CONFIG_PATH = Paths.get("config", SimplyScreens.MOD_ID + ".properties");
    private static final Properties properties = new Properties();

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

        VIEW_DISTANCE = getInt("viewDistance", VIEW_DISTANCE);
        SCREEN_TICK_RATE = getInt("screenTickRate", SCREEN_TICK_RATE);
        DISABLE_UPLOAD = getBoolean("disableUpload", DISABLE_UPLOAD);
        MAX_UPLOAD_SIZE = getInt("maxUploadSize", MAX_UPLOAD_SIZE);
    }

    private static void createDefaultConfig() throws IOException {
        Files.createDirectories(CONFIG_PATH.getParent());
        properties.setProperty("viewDistance", String.valueOf(VIEW_DISTANCE));
        properties.setProperty("screenTickRate", String.valueOf(SCREEN_TICK_RATE));
        properties.setProperty("disableUpload", String.valueOf(DISABLE_UPLOAD));
        properties.setProperty("maxUploadSize", String.valueOf(MAX_UPLOAD_SIZE));
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

    private static boolean getBoolean(String key, boolean defaultValue) {
        return Boolean.parseBoolean(properties.getProperty(key, String.valueOf(defaultValue)));
    }
}
