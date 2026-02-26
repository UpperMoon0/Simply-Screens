package com.nstut.simplyscreens;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry system for tracking screens by their IDs and managing their image associations.
 * This allows multiple separate screen structures to share the same image by using the same screen ID.
 */
public class ScreenRegistry {
    // Map of screen ID to image UUID
    private static final Map<String, UUID> screenIdToImageId = new ConcurrentHashMap<>();

    // Map of level to map of position to screen ID
    private static final Map<Level, Map<BlockPos, String>> levelScreenIds = new ConcurrentHashMap<>();

    // Gson instance for serialization
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Registry file path
    private static Path registryFilePath;

    // Flag to track if registry has been initialized
    private static boolean initialized = false;

    /**
     * Initializes the screen registry with the world save path.
     * Can be called multiple times - subsequent calls will be ignored.
     *
     * @param worldSavePath The path to the world save directory
     */
    public static void init(Path worldSavePath) {
        if (initialized) return;

        registryFilePath = worldSavePath.resolve("screen_registry.json");
        loadRegistry();
        initialized = true;
    }

    /**
     * Checks if the registry has been initialized.
     *
     * @return true if initialized, false otherwise
     */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Saves the screen registry to disk.
     */
    public static void saveRegistry() {
        if (registryFilePath == null) return;

        try {
            String json = GSON.toJson(screenIdToImageId);
            File file = registryFilePath.toFile();
            file.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(json);
            }
            SimplyScreens.LOGGER.info("Saved screen registry to {}", registryFilePath);
        } catch (IOException e) {
            SimplyScreens.LOGGER.error("Failed to save screen registry", e);
        }
    }

    /**
     * Loads the screen registry from disk.
     */
    public static void loadRegistry() {
        if (registryFilePath == null) return;

        try {
            File file = registryFilePath.toFile();
            if (file.exists()) {
                try (FileReader reader = new FileReader(file)) {
                    Type type = new TypeToken<Map<String, String>>(){}.getType();
                    Map<String, String> loadedStates = GSON.fromJson(reader, type);
                    if (loadedStates != null) {
                        screenIdToImageId.clear();
                        for (Map.Entry<String, String> entry : loadedStates.entrySet()) {
                            try {
                                screenIdToImageId.put(entry.getKey(), UUID.fromString(entry.getValue()));
                            } catch (IllegalArgumentException e) {
                                SimplyScreens.LOGGER.warn("Invalid UUID in screen registry: {}", entry.getValue());
                            }
                        }
                        SimplyScreens.LOGGER.info("Loaded screen registry with {} entries", screenIdToImageId.size());
                    }
                }
            } else {
                SimplyScreens.LOGGER.info("No existing screen registry file found, starting with empty registry");
            }
        } catch (Exception e) {
            SimplyScreens.LOGGER.error("Failed to load screen registry", e);
        }
    }

    /**
     * Gets the image ID for a screen ID.
     *
     * @param screenId The screen ID
     * @return The image UUID, or null if not found
     */
    public static UUID getImageId(String screenId) {
        return screenIdToImageId.get(screenId);
    }

    /**
     * Sets the image ID for a screen ID.
     *
     * @param screenId The screen ID
     * @param imageId The image UUID
     */
    public static void setImageId(String screenId, UUID imageId) {
        if (screenId == null || screenId.isEmpty()) return;
        if (imageId == null) {
            screenIdToImageId.remove(screenId);
        } else {
            screenIdToImageId.put(screenId, imageId);
        }
    }

    /**
     * Removes a screen ID from the registry.
     *
     * @param screenId The screen ID to remove
     */
    public static void removeScreenId(String screenId) {
        screenIdToImageId.remove(screenId);
    }

    /**
     * Gets all screen IDs.
     *
     * @return Set of all screen IDs
     */
    public static Set<String> getAllScreenIds() {
        return Set.copyOf(screenIdToImageId.keySet());
    }

    /**
     * Registers a screen with the registry.
     *
     * @param level The level the screen is in
     * @param pos The position of the screen
     * @param screenId The screen ID
     */
    public static void registerScreen(Level level, BlockPos pos, String screenId) {
        if (level.isClientSide()) return;
        if (screenId == null || screenId.isEmpty()) return;

        levelScreenIds.computeIfAbsent(level, k -> new ConcurrentHashMap<>()).put(pos, screenId);
        SimplyScreens.LOGGER.debug("Registered screen at {} with ID {}", pos, screenId);
    }

    /**
     * Unregisters a screen from the registry.
     *
     * @param level The level the screen is in
     * @param pos The position of the screen
     * @param screenId The screen ID
     */
    public static void unregisterScreen(Level level, BlockPos pos, String screenId) {
        if (level.isClientSide()) return;

        Map<BlockPos, String> levelMap = levelScreenIds.get(level);
        if (levelMap != null) {
            levelMap.remove(pos);
            if (levelMap.isEmpty()) {
                levelScreenIds.remove(level);
            }
        }

        SimplyScreens.LOGGER.debug("Unregistered screen at {} with ID {}", pos, screenId);
    }

    /**
     * Updates the screen ID for a registered screen.
     *
     * @param level The level the screen is in
     * @param pos The position of the screen
     * @param oldScreenId The old screen ID
     * @param newScreenId The new screen ID
     */
    public static void updateScreenId(Level level, BlockPos pos, String oldScreenId, String newScreenId) {
        if (level.isClientSide()) return;

        // Unregister with old ID
        unregisterScreen(level, pos, oldScreenId);

        // Register with new ID
        registerScreen(level, pos, newScreenId);

        SimplyScreens.LOGGER.debug("Updated screen ID at {} from {} to {}", pos, oldScreenId, newScreenId);
    }

    /**
     * Gets the screen ID at a position.
     *
     * @param level The level
     * @param pos The position
     * @return The screen ID, or null if not found
     */
    public static String getScreenIdAt(Level level, BlockPos pos) {
        Map<BlockPos, String> levelMap = levelScreenIds.get(level);
        if (levelMap != null) {
            return levelMap.get(pos);
        }
        return null;
    }

    /**
     * Clears all registry data for a level. Should be called when a level is unloaded.
     *
     * @param level The level to clear
     */
    public static void clearLevel(Level level) {
        if (level.isClientSide()) return;
        levelScreenIds.remove(level);
    }
}
