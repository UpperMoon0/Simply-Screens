package com.nstut.simplyscreens.api;

import com.nstut.simplyscreens.ScreenNetworkService;
import com.nstut.simplyscreens.ScreenNetworkService.Actor;
import com.nstut.simplyscreens.ScreenRegistry;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.helpers.ImageMetadata;
import com.nstut.simplyscreens.helpers.ScreenAccessService;
import com.nstut.simplyscreens.helpers.ScreenPlaylistRuntime;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import com.nstut.simplyscreens.playlist.PlaybackSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Supported integration entry point for server-side mods.
 *
 * All methods are synchronous and must be invoked on the actual Minecraft server thread.
 * Null player means anonymous automation: it can only control PUBLIC networks, and may
 * never read private media. Pass a real online ServerPlayer for actor-aware access.
 * Missing, unloaded or inaccessible targets fail closed; no method force-loads chunks.
 */
public final class ScreenApi {
    public record ImageInfo(UUID id, String displayName) { }
    public record DisplayInfo(String dimension, BlockPos position, String key, UUID displayId,
                              UUID selectedImage, int width, int height, boolean localOverride) { }
    private ScreenApi() { }

    public static String networkKey(String id) {
        return ScreenNetworkService.network(id);
    }

    private static boolean ready(MinecraftServer server) {
        return server != null && server.isSameThread() && ScreenRegistry.isInitialized()
                && ScreenPlaylistRuntime.service() != null;
    }

    private static boolean validCaller(MinecraftServer server, ServerPlayer player) {
        return player == null || player.level().getServer() == server
                && server.getPlayerList().getPlayer(player.getUUID()) == player;
    }

    static Actor actor(ServerPlayer player) {
        return player == null ? Actor.automation() : ScreenAccessService.actor(player);
    }

    static ScreenControlService control() {
        return new ScreenControlService(ScreenRegistry.networks(), ScreenPlaylistRuntime.service());
    }

    private static boolean canReadMetadata(Actor caller, ImageMetadata image) {
        return image != null && caller != null && (caller.operator() || image.getOwnerUUID() == null
                || caller.id() != null && image.getOwnerUUID().equals(caller.id().toString()));
    }

    static boolean canUseImage(MinecraftServer server, Actor caller, UUID id) {
        return id == null || canReadMetadata(caller, ServerImageManager.getImageMetadata(server, id));
    }

    public static List<ScreenControlService.Status> listNetworks(MinecraftServer server, ServerPlayer player) {
        if (!ready(server) || !validCaller(server, player)) return List.of();
        return control().list(actor(player));
    }

    public static ScreenControlService.Status status(MinecraftServer server, ServerPlayer player, String key) {
        if (!ready(server) || !validCaller(server, player)) return null;
        return control().status(key, actor(player));
    }

    /** Null image clears the network. Ownership and library reads are validated independently. */
    public static boolean selectImage(MinecraftServer server, ServerPlayer player, String key, UUID image) {
        if (!ready(server) || !validCaller(server, player)) return false;
        Actor caller = actor(player);
        if (!control().select(caller, key, image, id -> canUseImage(server, caller, id))) return false;
        ScreenAccessService.refreshLoadedContent(key);
        return true;
    }

    /** Play a saved, owner-authorized playlist as a synchronized network session. */
    public static boolean play(MinecraftServer server, ServerPlayer player, String key,
                               UUID playlistOwner, UUID playlistId) {
        if (!ready(server) || !validCaller(server, player)) return false;
        Actor caller = actor(player);
        if (!control().play(caller, key, playlistOwner, playlistId,
                id -> canUseImage(server, caller, id))) return false;
        reconcile(key);
        return true;
    }

    /** Supported actions: PAUSE, RESUME, STOP, NEXT, PREVIOUS, RESTART. */
    public static boolean transport(MinecraftServer server, ServerPlayer player, String key, String action) {
        if (!ready(server) || !validCaller(server, player)) return false;
        if (!control().transport(actor(player), key, action)) return false;
        reconcile(key);
        return true;
    }

    private static void reconcile(String key) {
        ScreenPlaylistRuntime.reconcile(key);
    }

    /** Only owned and legacy public images are returned, with bounded immutable metadata. */
    public static List<ImageInfo> listImages(MinecraftServer server, ServerPlayer player) {
        if (!ready(server) || !validCaller(server, player) || player == null) return List.of();
        Actor caller = actor(player);
        List<ImageInfo> allowed = new ArrayList<>();
        for (ImageMetadata media : ServerImageManager.getImageList(server)) {
            UUID id;
            try { id = UUID.fromString(media.getId()); } catch (RuntimeException invalid) { continue; }
            if (canReadMetadata(caller, media))
                allowed.add(new ImageInfo(id, media.getName()));
        }
        allowed.sort(Comparator.comparing(ImageInfo::displayName).thenComparing(ImageInfo::id));
        return List.copyOf(allowed.subList(0, Math.min(allowed.size(), 512)));
    }

    /** Inspect only an already-loaded block entity in a named dimension; never load a chunk. */
    public static DisplayInfo display(MinecraftServer server, ServerPlayer player,
                                      String dimension, BlockPos pos) {
        if (!ready(server) || !validCaller(server, player) || dimension == null || pos == null) return null;
        for (ServerLevel level : server.getAllLevels()) {
            if (!level.dimension().location().toString().equals(dimension)) continue;
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null || !(chunk.getBlockEntity(pos) instanceof ScreenBlockEntity display)) return null;
            String key = display.contentKey();
            var visible = control().status(key, actor(player));
            if (visible == null) return null;
            return new DisplayInfo(dimension, pos.immutable(), key, display.getDisplayId(),
                    visible.image(), display.getScreenWidth(), display.getScreenHeight(),
                    display.isLocalImageOverride());
        }
        return null;
    }
}
