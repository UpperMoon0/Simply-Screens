package com.nstut.simplyscreens;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

/**
 * Registry system for tracking screens by their IDs and managing their image associations.
 * This allows multiple separate screen structures to share the same image by using the same screen ID.
 * 
 * This implementation uses ScreenRegistryHelper for shared logic.
 */
public class ScreenRegistry {
    
    // Helper instance for shared logic
    private static final ScreenRegistryHelper HELPER = new ScreenRegistryHelper(SimplyScreens.LOGGER);
    
    private static final ScreenLinkIndex<Level, BlockPos> LINK_INDEX = new ScreenLinkIndex<>();

    /**
     * Initializes the screen registry with the world save path.
     * Can be called multiple times - subsequent calls will be ignored.
     *
     * @param worldSavePath The path to the world save directory
     */
    public static void init(java.nio.file.Path worldSavePath) {
        LINK_INDEX.clearAll();
        HELPER.init(worldSavePath);
    }

    /**
     * Checks if the registry has been initialized.
     *
     * @return true if initialized, false otherwise
     */
    public static boolean isInitialized() {
        return HELPER.isInitialized();
    }

    /**
     * Saves the screen registry to disk.
     */
    public static void saveRegistry() {
        HELPER.saveRegistry();
    }

    /**
     * Loads the screen registry from disk.
     */
    public static void loadRegistry() {
        HELPER.loadRegistry();
    }

    /**
     * Gets the image ID for a screen ID.
     *
     * @param screenId The screen ID
     * @return The image UUID, or null if not found
     */
    public static UUID getImageId(String screenId) {
        return HELPER.getImageId(screenId);
    }

    /**
     * Sets the image ID for a screen ID.
     *
     * @param screenId The screen ID
     * @param imageId The image UUID
     */
    public static void setImageId(String screenId, UUID imageId) {
        HELPER.setImageId(screenId, imageId);
    }

    /**
     * Removes a screen ID from the registry.
     *
     * @param screenId The screen ID to remove
     */
    public static void removeScreenId(String screenId) {
        HELPER.removeScreenId(screenId);
    }

    public static void removeImageReferences(UUID imageId) {
        if (HELPER.removeImageReferences(imageId)) HELPER.saveRegistry();
    }

    /**
     * Gets all screen IDs.
     *
     * @return Set of all screen IDs
     */
    public static java.util.Set<String> getAllScreenIds() {
        return HELPER.getAllScreenIds();
    }

    public static boolean claimScreenId(String screenId, UUID playerId, boolean administrator) {
        UUID previousOwner = HELPER.getScreenIdOwner(screenId);
        boolean allowed = HELPER.claimScreenId(screenId, playerId, administrator);
        if (allowed && previousOwner == null && HELPER.getScreenIdOwner(screenId) != null) HELPER.saveRegistry();
        return allowed;
    }

    public static boolean canWriteScreenId(String screenId, UUID playerId, boolean administrator) {
        return HELPER.canWriteScreenId(screenId, playerId, administrator);
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

        LINK_INDEX.register(level, pos, screenId);
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

        LINK_INDEX.unregister(level, pos);

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

        LINK_INDEX.update(level, pos, newScreenId);

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
        return LINK_INDEX.getScreenId(level, pos);
    }

    /**
     * Clears all registry data for a level. Should be called when a level is unloaded.
     *
     * @param level The level to clear
     */
    public static void clearLevel(Level level) {
        if (level.isClientSide()) return;
        LINK_INDEX.clear(level);
    }

    public static List<BlockPos> getPositionsForScreenId(Level level, String screenId) {
        if (level.isClientSide()) return List.of();
        return LINK_INDEX.getPositions(level, screenId);
    }

    public static void redirectAnchor(Level level, BlockPos from, BlockPos to) {
        if (level == null || level.isClientSide() || from == null || to == null) return;
        String dimension = getDimensionId(level);
        HELPER.redirectAnchor(dimension, from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ());
        HELPER.saveRegistry();
    }

    public static BlockPos resolveAnchorRedirect(Level level, BlockPos from) {
        if (level == null || from == null) return null;
        String dimension = getDimensionId(level);
        int[] target = HELPER.resolveAnchorRedirect(dimension, from.getX(), from.getY(), from.getZ());
        return target != null ? new BlockPos(target[0], target[1], target[2]) : null;
    }

    public static void removeAnchorRedirect(Level level, BlockPos pos) {
        if (level == null || pos == null) return;
        String dimension = getDimensionId(level);
        HELPER.removeAnchorRedirect(dimension, pos.getX(), pos.getY(), pos.getZ());
        HELPER.saveRegistry();
    }

    public static void clearAnchorRedirects(Level level) {
        if (level == null) {
            HELPER.clearAnchorRedirects(null);
        } else {
            HELPER.clearAnchorRedirects(getDimensionId(level));
        }
    }

    public static String getDimensionId(Level level) {
        if (level == null) return "minecraft:overworld";
        try {
            if (level.dimension() != null && level.dimension().location() != null) {
                return level.dimension().location().toString();
            }
        } catch (Exception ignored) {}
        return "minecraft:overworld";
    }
}
