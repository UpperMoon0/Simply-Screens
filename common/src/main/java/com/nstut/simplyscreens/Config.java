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
    public static boolean DISABLE_URL_DOWNLOAD = false;
    public static boolean DEBUG_RENDERING = false;
    public static int MAX_UPLOAD_SIZE = 5 * 1024 * 1024; // 5MB
    public static int MAX_URL_DOWNLOAD_SIZE = 10 * 1024 * 1024; // 10MB for URL downloads
    public static int MAX_IMAGES_PER_PLAYER = 256;
    public static long MAX_STORAGE_PER_PLAYER = 512L * 1024 * 1024;
    public static long MAX_STORAGE_TOTAL = 2L * 1024 * 1024 * 1024;

    public static final int MIN_UPLOAD_SIZE = 1024; // 1KB
    public static final int MAX_UPLOAD_SIZE_LIMIT = 100 * 1024 * 1024; // 100MB

    private static String modId = "simply_screens"; // default
    private static final Properties properties = new Properties();

    /**
     * Sets the mod ID for config file naming.
     * Must be called before load().
     */
    public static void setModId(String id) {
        modId = id;
    }
    
    private static Path getConfigPath() {
        return Paths.get("config", modId + ".properties");
    }

    public static void load() {
        try {
            Path path = getConfigPath();
            if (Files.notExists(path)) {
                createDefaultConfig(path);
            }
            try (FileInputStream stream = new FileInputStream(path.toFile())) {
                properties.load(stream);
            }
        } catch (IOException e) {
            System.err.println("Failed to load config file: " + e.getMessage());
        }

        VIEW_DISTANCE = clamp(getInt("viewDistance", VIEW_DISTANCE), 8, 512);
        SCREEN_TICK_RATE = clamp(getInt("screenTickRate", SCREEN_TICK_RATE), 1, 72_000);
        DISABLE_UPLOAD = getBoolean("disableUpload", DISABLE_UPLOAD);
        DISABLE_URL_DOWNLOAD = getBoolean("disableUrlDownload", DISABLE_URL_DOWNLOAD);
        DEBUG_RENDERING = getBoolean("debugRendering", DEBUG_RENDERING);
        MAX_UPLOAD_SIZE = clamp(getInt("maxUploadSize", MAX_UPLOAD_SIZE), MIN_UPLOAD_SIZE, MAX_UPLOAD_SIZE_LIMIT);
        MAX_URL_DOWNLOAD_SIZE = clamp(getInt("maxUrlDownloadSize", MAX_URL_DOWNLOAD_SIZE), MIN_UPLOAD_SIZE, MAX_UPLOAD_SIZE_LIMIT);
        MAX_IMAGES_PER_PLAYER = clamp(getInt("maxImagesPerPlayer", MAX_IMAGES_PER_PLAYER), 1, 100_000);
        MAX_STORAGE_PER_PLAYER = getLong("maxStoragePerPlayer", MAX_STORAGE_PER_PLAYER, MIN_UPLOAD_SIZE, Long.MAX_VALUE);
        MAX_STORAGE_TOTAL = getLong("maxStorageTotal", MAX_STORAGE_TOTAL, MIN_UPLOAD_SIZE, Long.MAX_VALUE);
    }

    private static void createDefaultConfig(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        properties.setProperty("viewDistance", String.valueOf(VIEW_DISTANCE));
        properties.setProperty("screenTickRate", String.valueOf(SCREEN_TICK_RATE));
        properties.setProperty("disableUpload", String.valueOf(DISABLE_UPLOAD));
        properties.setProperty("disableUrlDownload", String.valueOf(DISABLE_URL_DOWNLOAD));
        properties.setProperty("debugRendering", String.valueOf(DEBUG_RENDERING));
        properties.setProperty("maxUploadSize", String.valueOf(MAX_UPLOAD_SIZE));
        properties.setProperty("maxUrlDownloadSize", String.valueOf(MAX_URL_DOWNLOAD_SIZE));
        properties.setProperty("maxImagesPerPlayer", String.valueOf(MAX_IMAGES_PER_PLAYER));
        properties.setProperty("maxStoragePerPlayer", String.valueOf(MAX_STORAGE_PER_PLAYER));
        properties.setProperty("maxStorageTotal", String.valueOf(MAX_STORAGE_TOTAL));
        try (FileOutputStream stream = new FileOutputStream(path.toFile())) {
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long getLong(String key, long defaultValue, long min, long max) {
        try {
            long value = Long.parseLong(properties.getProperty(key, String.valueOf(defaultValue)));
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
