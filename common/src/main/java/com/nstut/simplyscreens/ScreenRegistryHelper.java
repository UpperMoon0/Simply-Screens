package com.nstut.simplyscreens;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Helper class containing shared screen registry logic.
 * This can be used by both 1.20.1 and 1.21.1 implementations.
 */
public class ScreenRegistryHelper {
    
    // Map of screen ID to image UUID
    protected final Map<String, UUID> screenIdToImageId = new ConcurrentHashMap<>();

    // Gson instance for serialization
    protected static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Registry file path
    protected Path registryFilePath;

    // Flag to track if registry has been initialized
    protected boolean initialized = false;
    
    // Logger reference
    protected final org.slf4j.Logger logger;

    public ScreenRegistryHelper(org.slf4j.Logger logger) {
        this.logger = logger;
    }

    /**
     * Initializes the screen registry with the world save path.
     * Can be called multiple times - subsequent calls will be ignored.
     *
     * @param worldSavePath The path to the world save directory
     */
    public synchronized void init(Path worldSavePath) {
        Path nextPath = worldSavePath.resolve("screen_registry.json").toAbsolutePath().normalize();
        if (initialized && nextPath.equals(registryFilePath)) return;
        if (initialized) saveRegistry();
        screenIdToImageId.clear();
        registryFilePath = nextPath;
        loadRegistry();
        initialized = true;
    }

    /**
     * Checks if the registry has been initialized.
     *
     * @return true if initialized, false otherwise
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Saves the screen registry to disk.
     */
    public void saveRegistry() {
        if (registryFilePath == null) return;

        try {
            String json = GSON.toJson(screenIdToImageId);
            File file = registryFilePath.toFile();
            file.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(json);
            }
            logger.info("Saved screen registry to {}", registryFilePath);
        } catch (IOException e) {
            logger.error("Failed to save screen registry", e);
        }
    }

    /**
     * Loads the screen registry from disk.
     */
    public void loadRegistry() {
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
                                logger.warn("Invalid UUID in screen registry: {}", entry.getValue());
                            }
                        }
                        logger.info("Loaded screen registry with {} entries", screenIdToImageId.size());
                    }
                }
            } else {
                logger.info("No existing screen registry file found, starting with empty registry");
            }
        } catch (Exception e) {
            logger.error("Failed to load screen registry", e);
        }
    }

    /**
     * Gets the image ID for a screen ID.
     *
     * @param screenId The screen ID
     * @return The image UUID, or null if not found
     */
    public UUID getImageId(String screenId) {
        return screenIdToImageId.get(screenId);
    }

    /**
     * Sets the image ID for a screen ID.
     *
     * @param screenId The screen ID
     * @param imageId The image UUID
     */
    public void setImageId(String screenId, UUID imageId) {
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
    public void removeScreenId(String screenId) {
        screenIdToImageId.remove(screenId);
    }

    /**
     * Gets all screen IDs.
     *
     * @return Set of all screen IDs
     */
    public Set<String> getAllScreenIds() {
        return Set.copyOf(screenIdToImageId.keySet());
    }
}
