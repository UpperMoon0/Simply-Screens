package com.nstut.simplyscreens;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
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
    // Map of dimension -> (fromPos -> toPos) for persistent anchor redirects
    protected final Map<String, Map<String, String>> anchorRedirects = new ConcurrentHashMap<>();

    // Gson instance for serialization
    protected static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Registry file path
    protected Path registryFilePath;
    protected Path ownersFilePath;
    protected Path redirectsFilePath;

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
        anchorRedirects.clear();
        registryFilePath = nextPath;
        ownersFilePath = worldSavePath.resolve("screen_registry_owners.json").toAbsolutePath().normalize();
        redirectsFilePath = worldSavePath.resolve("anchor_redirects.json").toAbsolutePath().normalize();
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
            Files.createDirectories(registryFilePath.getParent());
            atomicWriteWithBackup(registryFilePath, GSON.toJson(screenIdToImageId));
            atomicWriteWithBackup(ownersFilePath, GSON.toJson(screenIdToOwnerId));
            atomicWriteWithBackup(redirectsFilePath, GSON.toJson(anchorRedirects));
            logger.info("Saved screen registry to {}", registryFilePath);
        } catch (IOException e) {
            logger.error("Failed to save screen registry", e);
        }
    }

    /**
     * Loads the screen registry from disk with backup recovery support.
     */
    public void loadRegistry() {
        if (registryFilePath == null) return;

        try {
            Type idMapType = new TypeToken<Map<String, String>>(){}.getType();
            Map<String, String> loadedStates = loadJsonWithBackup(registryFilePath, idMapType);
            screenIdToImageId.clear();
            if (loadedStates != null) {
                for (Map.Entry<String, String> entry : loadedStates.entrySet()) {
                    try {
                        screenIdToImageId.put(entry.getKey(), UUID.fromString(entry.getValue()));
                    } catch (IllegalArgumentException e) {
                        logger.warn("Invalid UUID in screen registry: {}", entry.getValue());
                    }
                }
                logger.info("Loaded screen registry with {} entries", screenIdToImageId.size());
            } else {
                logger.info("No existing screen registry file found, starting with empty registry");
            }

            Map<String, String> loadedOwners = loadJsonWithBackup(ownersFilePath, idMapType);
            screenIdToOwnerId.clear();
            if (loadedOwners != null) {
                loadedOwners.forEach((id, owner) -> {
                    try {
                        screenIdToOwnerId.put(id, UUID.fromString(owner));
                    } catch (IllegalArgumentException e) {
                        logger.warn("Invalid owner UUID in screen registry: {}", owner);
                    }
                });
            }

            Type redirectsType = new TypeToken<Map<String, Map<String, String>>>(){}.getType();
            Map<String, Map<String, String>> loadedRedirects = loadJsonWithBackup(redirectsFilePath, redirectsType);
            anchorRedirects.clear();
            if (loadedRedirects != null) {
                loadedRedirects.forEach((dim, map) -> {
                    if (map != null) {
                        anchorRedirects.put(dim, new ConcurrentHashMap<>(map));
                    }
                });
            }
        } catch (Exception e) {
            logger.error("Failed to load screen registry", e);
        }
    }

    private <T> T loadJsonWithBackup(Path primaryPath, Type type) {
        if (primaryPath == null) return null;
        if (Files.exists(primaryPath)) {
            try (FileReader reader = new FileReader(primaryPath.toFile())) {
                T data = GSON.fromJson(reader, type);
                if (data != null) return data;
            } catch (Exception e) {
                logger.warn("Failed to load {}, attempting recovery from backup", primaryPath, e);
            }
        }
        Path backupPath = primaryPath.resolveSibling(primaryPath.getFileName() + ".bak");
        if (Files.exists(backupPath)) {
            try (FileReader reader = new FileReader(backupPath.toFile())) {
                T data = GSON.fromJson(reader, type);
                if (data != null) {
                    logger.info("Successfully recovered {} from backup", primaryPath.getFileName());
                    return data;
                }
            } catch (Exception e) {
                logger.error("Failed to load backup {}", backupPath, e);
            }
        }
        return null;
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

    public boolean removeImageReferences(UUID imageId) {
        if (imageId == null) return false;
        return screenIdToImageId.entrySet().removeIf(entry -> imageId.equals(entry.getValue()));
    }

    /**
     * Gets all screen IDs.
     *
     * @return Set of all screen IDs
     */
    public Set<String> getAllScreenIds() {
        return Set.copyOf(screenIdToImageId.keySet());
    }

    public static String formatPos(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    public static int[] parsePos(String str) {
        if (str == null) return null;
        String[] parts = str.split(",");
        if (parts.length != 3) return null;
        try {
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Registers an anchor redirect from oldAnchor to newAnchor for a dimension.
     */
    public void redirectAnchor(String dimension, int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        if (dimension == null || dimension.isEmpty()) dimension = "minecraft:overworld";
        String from = formatPos(fromX, fromY, fromZ);
        String to = formatPos(toX, toY, toZ);
        if (from.equals(to)) return;

        Map<String, String> dimMap = anchorRedirects.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>());

        // If 'to' already points to another redirect, resolve to the final destination
        String finalTarget = resolveAnchorRedirectString(dimension, to);
        if (finalTarget != null) {
            to = finalTarget;
        }
        if (from.equals(to)) return;

        dimMap.put(from, to);

        // Update any existing redirects that pointed to 'from' to point directly to 'to'
        for (Map.Entry<String, String> entry : dimMap.entrySet()) {
            if (entry.getValue().equals(from)) {
                entry.setValue(to);
            }
        }
    }

    /**
     * Resolves an anchor redirect for a dimension. Returns the final target coordinates, or null if no redirect.
     */
    public int[] resolveAnchorRedirect(String dimension, int x, int y, int z) {
        String res = resolveAnchorRedirectString(dimension, formatPos(x, y, z));
        return res != null ? parsePos(res) : null;
    }

    public String resolveAnchorRedirectString(String dimension, String from) {
        if (dimension == null || dimension.isEmpty()) dimension = "minecraft:overworld";
        Map<String, String> dimMap = anchorRedirects.get(dimension);
        if (dimMap == null || from == null) return null;

        String current = from;
        Set<String> visited = new HashSet<>();
        visited.add(current);

        String target = dimMap.get(current);
        if (target == null) return null;

        while (target != null) {
            current = target;
            if (!visited.add(current)) {
                // Cycle detected
                break;
            }
            target = dimMap.get(current);
        }

        // Path compression
        if (!current.equals(from)) {
            dimMap.put(from, current);
            return current;
        }
        return null;
    }

    public void removeAnchorRedirect(String dimension, int x, int y, int z) {
        if (dimension == null || dimension.isEmpty()) dimension = "minecraft:overworld";
        Map<String, String> dimMap = anchorRedirects.get(dimension);
        if (dimMap != null) {
            dimMap.remove(formatPos(x, y, z));
            if (dimMap.isEmpty()) {
                anchorRedirects.remove(dimension);
            }
        }
    }

    public void clearAnchorRedirects(String dimension) {
        if (dimension == null || dimension.isEmpty()) {
            anchorRedirects.clear();
        } else {
            anchorRedirects.remove(dimension);
        }
    }

    private static void atomicWriteWithBackup(Path target, String contents) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Path backup = target.resolveSibling(target.getFileName() + ".bak");
        Files.writeString(temporary, contents, StandardCharsets.UTF_8);
        if (Files.exists(target)) Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
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
