package com.nstut.simplyscreens.client;

import com.nstut.simplyscreens.SimplyScreens;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side registry for tracking screen IDs and their associated image UUIDs.
 * This allows quick lookups during rendering without server queries.
 */
public class ClientScreenRegistry {
    // Map of screen ID to image UUID
    private static final Map<String, UUID> screenIdToImageId = new ConcurrentHashMap<>();

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
        SimplyScreens.LOGGER.debug("Client: Set screen ID '{}' to image {}", screenId, imageId);
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
     * Removes a screen ID from the registry.
     *
     * @param screenId The screen ID to remove
     */
    public static void removeScreenId(String screenId) {
        screenIdToImageId.remove(screenId);
    }

    /**
     * Updates all screen IDs from server data.
     *
     * @param screenIds Map of screen IDs to image UUIDs
     */
    public static void updateFromServer(Map<String, UUID> screenIds) {
        screenIdToImageId.clear();
        if (screenIds != null) {
            screenIdToImageId.putAll(screenIds);
        }
        SimplyScreens.LOGGER.info("Client: Updated screen registry with {} entries", screenIdToImageId.size());
    }

    /**
     * Clears all registry data. Should be called when the client disconnects.
     */
    public static void clear() {
        screenIdToImageId.clear();
    }
}
