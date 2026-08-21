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
    public static final int MAX_SCREEN_ID_LENGTH = 64;
    public static final int MAX_SCREEN_IDS_PER_PLAYER = 64;
    private static final java.util.regex.Pattern VALID_SCREEN_ID =
            java.util.regex.Pattern.compile("[A-Za-z0-9._:-]{1," + MAX_SCREEN_ID_LENGTH + "}");
    
    // Map of screen ID to image UUID
    protected final Map<String, UUID> screenIdToImageId = new ConcurrentHashMap<>();
    protected final Map<String, UUID> screenIdToOwnerId = new ConcurrentHashMap<>();

    // Gson instance for serialization
    protected static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Registry file path
    protected Path registryFilePath;
    protected Path ownersFilePath;

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
        screenIdToOwnerId.clear();
        registryFilePath = nextPath;
        ownersFilePath = worldSavePath.resolve("screen_registry_owners.json").toAbsolutePath().normalize();
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
            try (FileWriter writer = new FileWriter(ownersFilePath.toFile())) {
                writer.write(GSON.toJson(screenIdToOwnerId));
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
            File ownersFile = ownersFilePath == null ? null : ownersFilePath.toFile();
            if (ownersFile != null && ownersFile.exists()) {
                try (FileReader reader = new FileReader(ownersFile)) {
                    Type type = new TypeToken<Map<String, String>>(){}.getType();
                    Map<String, String> loadedOwners = GSON.fromJson(reader, type);
                    screenIdToOwnerId.clear();
                    if (loadedOwners != null) loadedOwners.forEach((id, owner) -> {
                        try { screenIdToOwnerId.put(id, UUID.fromString(owner)); }
                        catch (IllegalArgumentException e) { logger.warn("Invalid owner UUID in screen registry: {}", owner); }
                    });
                }
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

    public boolean claimScreenId(String screenId, UUID playerId, boolean administrator) {
        screenId = normalizeScreenId(screenId);
        if (screenId.isEmpty() || playerId == null) return false;
        UUID owner = screenIdToOwnerId.get(screenId);
        if (owner != null) return administrator || owner.equals(playerId);
        // Existing owner-less IDs are legacy data and require an administrator to claim.
        if (screenIdToImageId.containsKey(screenId) && !administrator) return false;
        long ownedIds = screenIdToOwnerId.values().stream().filter(playerId::equals).count();
        if (!administrator && ownedIds >= MAX_SCREEN_IDS_PER_PLAYER) return false;
        screenIdToOwnerId.put(screenId, playerId);
        return true;
    }

    public boolean canWriteScreenId(String screenId, UUID playerId, boolean administrator) {
        screenId = normalizeScreenId(screenId);
        if (screenId.isEmpty()) return true;
        UUID owner = screenIdToOwnerId.get(screenId);
        return administrator || (owner != null && owner.equals(playerId));
    }

    public UUID getScreenIdOwner(String screenId) {
        return screenIdToOwnerId.get(normalizeScreenId(screenId));
    }

    public static String normalizeScreenId(String screenId) {
        if (screenId == null) return "";
        String normalized = screenId.strip();
        return VALID_SCREEN_ID.matcher(normalized).matches() ? normalized : "";
    }
}
