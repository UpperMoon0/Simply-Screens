package com.nstut.simplyscreens.helpers;
import com.nstut.simplyscreens.*;
import com.nstut.simplyscreens.ScreenNetworkService.*;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import java.util.*;
/** Actor-aware entry point for player and future automation adapters. Server thread only. */
public final class ScreenAccessService {
    private static final Map<UUID, Set<ScreenBlockEntity>> MEDIA = new HashMap<>();
    private static final Map<ScreenBlockEntity, UUID> INDEXED = new IdentityHashMap<>();
    private ScreenAccessService() { }
    public static Actor actor(ServerPlayer player) { return new Actor(player.getUUID(), player.level().getServer().getPlayerList().isOp(player.nameAndId())); }
    public static State state(ScreenBlockEntity screen) {
        return ScreenRegistry.networks().ensure(screen.accessKey(), screen.getImageId(), null);
    }
    public static boolean allowed(ServerPlayer player, ScreenBlockEntity screen, Permission permission) {
        return ScreenRegistry.networks().allowed(screen.accessKey(), actor(player), permission);
    }
    public static boolean select(ServerPlayer player, ScreenBlockEntity screen, UUID image) {
        if (!allowed(player, screen, Permission.CONTROL) || image != null && !library(player, image)) return false;
        if (screen.isLocalImageOverride()) { screen.setImageId(image); return true; }
        State s = state(screen);
        if (!ScreenRegistry.networks().select(screen.accessKey(), actor(player), image, s.revision())) return false;
        screen.setImageId(image);
        return true;
    }
    public static boolean library(ServerPlayer player, UUID image) {
        ImageMetadata metadata = ServerImageManager.getImageMetadata(player.level().getServer(), image);
        return metadata != null && (actor(player).operator() || metadata.getOwnerUUID() == null
                || metadata.getOwnerUUID().equals(player.getUUID().toString()));
    }
    public static boolean relink(ServerPlayer player, ScreenBlockEntity anchor, String id) {
        if (!allowed(player, anchor, Permission.MANAGE)) return false;
        if (id.equals(anchor.getScreenId())) return true;
        if (id.isEmpty()) {
            UUID image = anchor.getResolvedImageId();
            ScreenRegistry.detachDisplay(anchor.getDisplayId(), anchor.accessKey(), image);
            anchor.setScreenId("");
            anchor.setImageId(image);
            return true;
        }
        String key = ScreenNetworkService.network(id);
        State target = ScreenRegistry.networks().snapshot(key);
        if (target == null) {
            UUID image = anchor.getImageId();
            if (image != null && !library(player, image)) return false;
            if (!ScreenRegistry.networks().createOwned(key, player.getUUID(), image)) return false;
        } else if (!ScreenRegistry.networks().allowed(key, actor(player), Permission.MANAGE)) return false;
        anchor.setScreenId(id);
        if (!anchor.isLocalImageOverride()) anchor.setImageId(ScreenRegistry.networks().snapshot(key).image());
        return true;
    }
    public static void refreshMedia(ScreenBlockEntity screen) {
        if (screen.getLevel() == null || screen.getLevel().isClientSide() || screen.isRemoved()) return;
        state(screen);
        UUID next = screen.getResolvedImageId();
        if (INDEXED.containsKey(screen) && Objects.equals(INDEXED.get(screen), next)) return;
        untrack(screen);
        // Blank displays must remain registered so a later selection can reach their loaded children.
        INDEXED.put(screen, next);
        if (next != null) MEDIA.computeIfAbsent(next, ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(screen);
    }
    /** Event-driven content reconciliation, including loaded children whose anchor chunk is absent. */
    public static void refreshLoadedContent(String key) {
        for (ScreenBlockEntity screen : new ArrayList<>(INDEXED.keySet())) {
            if (screen.isRemoved() || !(screen.getLevel() instanceof ServerLevel level)) {
                untrack(screen);
                continue;
            }
            var pos = screen.getBlockPos();
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null || chunk.getBlockEntity(pos) != screen) {
                untrack(screen);
                continue;
            }
            if (key == null || key.equals(screen.contentKey())) screen.refreshAuthoritativeContent();
        }
    }
    public static void clearLevel(net.minecraft.world.level.Level level) {
        for (ScreenBlockEntity screen : new ArrayList<>(INDEXED.keySet())) if (screen.getLevel() == level) untrack(screen);
    }
    public static void untrack(ScreenBlockEntity screen) {
        UUID previous = INDEXED.remove(screen);
        Set<ScreenBlockEntity> refs = MEDIA.get(previous);
        if (refs != null) { refs.remove(screen); if (refs.isEmpty()) MEDIA.remove(previous); }
    }
    public static boolean canReadImage(ServerPlayer player, UUID image) {
        if (player == null || player.level().getServer().getPlayerList().getPlayer(player.getUUID()) != player) return false;
        if (library(player, image)) return true;
        Set<ScreenBlockEntity> refs = MEDIA.get(image);
        if (refs == null) return false;
        for (ScreenBlockEntity tile : refs) {
            if (tile.isRemoved() || tile.getLevel() != player.level() || !image.equals(tile.getResolvedImageId())) continue;
            ServerLevel level = (ServerLevel) tile.getLevel();
            var pos = tile.getBlockPos();
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null || chunk.getBlockEntity(pos) != tile) continue;
            if (!level.getChunkSource().chunkMap.getPlayers(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4), false).contains(player)) continue;
            if (allowed(player, tile, Permission.VIEW)) return true;
        }
        return false;
    }
    public static void access(ServerPlayer player, com.nstut.simplyscreens.network.ScreenAccessC2SPacket packet) {
        ScreenBlockEntity screen = ScreenPacketSecurity.nearby(player, packet.pos);
        if (screen == null) return;
        State current = state(screen);
        boolean changed = false;
        if (!"GET".equals(packet.action) && screen.accessKey().equals(packet.key) && current.revision() == packet.revision) {
            if ("OVERRIDE".equals(packet.action) && allowed(player, screen, Permission.MANAGE) && ("true".equals(packet.value) || "false".equals(packet.value))) {
                screen.useLocalImage(Boolean.parseBoolean(packet.value)); changed = true;
            } else if (java.util.Set.of("TRUST", "UNTRUST", "TRANSFER").contains(packet.action)) {
                if (allowed(player, screen, Permission.MANAGE)) {
                    UUID target = ScreenPlayerInput.resolve(packet.value, name -> {
                        var online = player.level().getServer().getPlayerList().getPlayerByName(name);
                        return online == null ? null : online.getUUID();
                    });
                    if (target != null) changed = ScreenRegistry.networks().access(packet.key, actor(player), packet.revision, packet.action, target.toString());
                }
            } else changed = "CLAIM".equals(packet.action) ? ScreenRegistry.networks().claim(packet.key, actor(player))
                    : ScreenRegistry.networks().access(packet.key, actor(player), packet.revision, packet.action, packet.value);
        }
        boolean manage = allowed(player, screen, Permission.MANAGE);
        State next = state(screen);
        // Trusted-player UUIDs are management data, not a public roster.
        State safe = manage ? next : new State(next.owner(), Set.of(), next.view(), next.control(),
                null, next.mode(), next.slide(), next.offsetMillis(), next.revision());
        var names = new HashMap<UUID, String>();
        var visiblePlayers = new HashSet<>(safe.trusted());
        if (safe.owner() != null) visiblePlayers.add(safe.owner());
        for (UUID id : visiblePlayers) {
            var online = player.level().getServer().getPlayerList().getPlayer(id);
            if (online != null) names.put(id, online.getName().getString());
        }
        var response = new com.nstut.simplyscreens.ScreenAccessSnapshot(screen.accessKey(), safe, manage,
                "GET".equals(packet.action) || changed, screen.isLocalImageOverride(), names);
        com.nstut.simplyscreens.network.PacketRegistries.sendToPlayer(player,
                new com.nstut.simplyscreens.network.ScreenAccessS2CPacket(packet.pos, response.toJson()));
    }
    public static boolean canMerge(ScreenBlockEntity first, ScreenBlockEntity second) {
        State a = state(first), b = state(second);
        return Objects.equals(a.owner(), b.owner()) && a.view() == b.view() && a.control() == b.control() && a.trusted().equals(b.trusted());
    }
}
