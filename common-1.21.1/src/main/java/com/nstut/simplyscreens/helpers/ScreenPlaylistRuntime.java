package com.nstut.simplyscreens.helpers;

import com.nstut.simplyscreens.ScreenRegistry;
import com.nstut.simplyscreens.ScreenNetworkService.Permission;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.network.*;
import com.nstut.simplyscreens.playlist.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.nio.file.Path;
import java.util.*;

/** All native authorization and world interaction stays on the server thread. */
public final class ScreenPlaylistRuntime {
    private static ScreenPlaylistService service;
    private static long lastTick, lastSave, lastPrefetch;
    private static final Map<UUID, long[]> REQUEST_BUDGET = new HashMap<>();
    private ScreenPlaylistRuntime() { }
    public static ScreenPlaylistService service() { return service; }
    public static void init(Path root) {
        ScreenPlaylistService next = new ScreenPlaylistService();
        next.init(root);
        service = next; lastTick = 0; lastSave = 0; lastPrefetch = 0; REQUEST_BUDGET.clear();
        // Restored playback is authoritative before any chunk is loaded.
        next.snapshots().forEach(ScreenPlaylistRuntime::apply);
    }
    public static void shutdown() {
        if (service != null) service.save();
        service = null; lastTick = 0; REQUEST_BUDGET.clear();
    }
    public static void tick(MinecraftServer server) {
        if (service == null) return;
        long now = System.nanoTime();
        long elapsed = lastTick == 0 ? 0 : Math.max(0, (now - lastTick) / 1_000_000L);
        lastTick = now;
        service.tick(elapsed).forEach(ScreenPlaylistRuntime::apply);
        if (lastPrefetch == 0 || now - lastPrefetch >= 2_000_000_000L) {
            ScreenAccessService.prefetch(server); lastPrefetch = now;
        }
        if (lastSave == 0 || now - lastSave >= 5_000_000_000L) {
            service.save(); lastSave = now;
        }
    }
    private static void apply(String key, PlaybackSnapshot snapshot) {
        if (ScreenRegistry.networks().snapshot(key) != null)
            ScreenRegistry.reconcileContent(key, snapshot.currentImage());
    }
    public static void stop(String key) {
        if (service == null) return;
        var state = service.snapshot(key);
        if (state.status() != PlaybackSnapshot.Status.STOPPED || !state.queue().isEmpty())
            service.command(key, "STOP", state.revision());
    }
    public static void purgeImage(UUID image) {
        if (service == null) return;
        service.purgeImage(image);
        service.snapshots().forEach(ScreenPlaylistRuntime::apply);
    }
    public static boolean upcoming(String key, UUID image) {
        return service != null && service.snapshot(key).upcomingImages().contains(image);
    }
    private static boolean budget(ServerPlayer player) {
        long second = System.nanoTime() / 1_000_000_000L;
        long[] count = REQUEST_BUDGET.computeIfAbsent(player.getUUID(), ignored -> new long[]{second, 0});
        if (count[0] != second) { count[0] = second; count[1] = 0; }
        return ++count[1] <= 12;
    }
    public static void handle(ServerPlayer player, BlockPos pos, String json) {
        if (service == null || !budget(player)) return;
        ScreenBlockEntity screen = ScreenPacketSecurity.nearby(player, pos);
        if (screen == null || !ScreenAccessService.allowed(player, screen, Permission.VIEW)) return;
        PlaylistWire.Request request;
        try { request = PlaylistWire.Request.parse(json); } catch (RuntimeException invalid) { return; }
        String key = screen.contentKey();
        UUID actor = player.getUUID(), selected = request.playlist();
        boolean control = ScreenAccessService.allowed(player, screen, Permission.CONTROL);
        boolean success = "GET".equals(request.action());
        if (!success && control && key.equals(request.key())) {
            try {
                var library = service.library(actor);
                NamedPlaylist saved = library.find(selected);
                List<PlaylistSlide> slides = saved == null ? new ArrayList<>() : new ArrayList<>(saved.slides());
                long revision = request.libraryRevision(), playback = request.playbackRevision();
                switch (request.action()) {
                    case "CREATE" -> { selected = service.create(actor, request.value(), revision); success = selected != null; }
                    case "RENAME" -> success = service.rename(actor, selected, request.value(), revision);
                    case "DUPLICATE" -> { selected = service.duplicate(actor, selected, request.value(), revision); success = selected != null; }
                    case "DELETE" -> success = service.delete(actor, selected, revision);
                    case "ADD", "DURATION", "REMOVE", "MOVE" -> {
                        if (saved == null) break;
                        int index = request.index();
                        switch (request.action()) {
                            case "ADD" -> {
                                if (!ScreenAccessService.library(player, request.image())) throw new IllegalArgumentException("Image is not in your library");
                                slides.add(new PlaylistSlide(request.image(), request.durationMillis()));
                            }
                            case "DURATION" -> slides.set(index, new PlaylistSlide(slides.get(index).image(), request.durationMillis()));
                            case "REMOVE" -> slides.remove(index);
                            case "MOVE" -> { var slide = slides.remove(index); slides.add(request.destination(), slide); }
                        }
                        success = service.replaceSlides(actor, selected, slides, revision);
                    }
                    case "PLAY" -> {
                        if (saved != null && !saved.slides().isEmpty()
                                && saved.slides().stream().allMatch(s -> ScreenAccessService.library(player, s.image())))
                            success = service.play(key, actor, selected, playback);
                    }
                    case "QUEUE_NEXT", "QUEUE_LAST", "ANNOUNCE" -> {
                        if (ScreenAccessService.library(player, request.image())) {
                            var slide = new PlaylistSlide(request.image(), request.durationMillis());
                            success = "ANNOUNCE".equals(request.action()) ? service.announce(key, slide, playback)
                                    : service.queue(key, slide, "QUEUE_NEXT".equals(request.action()), playback);
                        }
                    }
                    case "CLEAR_QUEUE" -> success = service.clearQueue(key, playback);
                    case "REMOVE_QUEUE" -> success = service.removeQueued(key, request.index(), playback);
                    case "MOVE_QUEUE" -> success = service.moveQueued(key, request.index(), request.destination(), playback);
                    case "REPEAT" -> {
                        var current = service.snapshot(key);
                        success = service.modes(key, RepeatMode.valueOf(request.value()), current.shuffle(), playback);
                    }
                    case "SHUFFLE" -> {
                        var current = service.snapshot(key);
                        if (Set.of("true", "false").contains(request.value()))
                            success = service.modes(key, current.repeat(), Boolean.parseBoolean(request.value()), playback);
                    }
                    default -> success = service.command(key, request.action(), playback);
                }
                if (success && !Set.of("CREATE", "RENAME", "DUPLICATE", "DELETE", "ADD", "DURATION", "REMOVE", "MOVE").contains(request.action()))
                    apply(key, service.snapshot(key));
            } catch (IllegalArgumentException | IndexOutOfBoundsException invalid) {
                success = false;
            } catch (IllegalStateException failure) {
                SimplyScreens.LOGGER.error("Playlist mutation could not be persisted", failure); success = false;
            }
        }
        var library = service.library(actor);
        NamedPlaylist saved = library.find(selected);
        if (saved == null) saved = library.playlists().isEmpty() ? null : library.playlists().get(0);
        var playback = service.snapshot(key);
        var view = new PlaylistWire.View(key, ScreenAccessService.state(screen).revision(), library.revision(), playback.revision(),
                request.requestId(), success, control, saved == null ? null : saved.id(),
                library.playlists().stream().map(p -> new PlaylistWire.Summary(p.id(), p.name(), p.slides().size())).toList(),
                saved == null ? List.of() : saved.slides().stream().map(ScreenPlaylistRuntime::slide).toList(),
                playback.announcing() ? "ANNOUNCEMENT" : playback.status().name(), playback.currentImage(), playback.slideIndex(),
                playback.offsetMillis(), playback.repeat().name(), playback.shuffle(),
                playback.queue().stream().map(ScreenPlaylistRuntime::slide).toList(), playback.upcomingImages());
        PacketRegistries.sendToPlayer(player, new ScreenPlaylistS2CPacket(pos, view.json()));
    }
    private static PlaylistWire.Slide slide(PlaylistSlide s) { return new PlaylistWire.Slide(s.image(), s.durationMillis()); }
}
