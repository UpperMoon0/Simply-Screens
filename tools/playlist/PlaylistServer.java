package com.nstut.simplyscreens.testing.playlist;

import com.google.gson.*;
import com.nstut.simplyscreens.Config;
import com.nstut.simplyscreens.ScreenRegistry;
import com.nstut.simplyscreens.blocks.BlockRegistries;
import com.nstut.simplyscreens.blocks.ScreenBlock;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.helpers.ScreenAccessService;
import com.nstut.simplyscreens.helpers.ScreenPlaylistRuntime;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import com.nstut.simplyscreens.playlist.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import java.nio.file.*;
import java.util.*;

/** Test-only dedicated-server observer, injected into a disposable checkout by playlist_test.py. */
public final class PlaylistServer {
    private static final Path DIR = Path.of(System.getenv("SS_PLAYLIST_DIR"));
    private static final Gson JSON = new Gson();
    private static final String BOOT = UUID.randomUUID().toString();
    private static final String LINK = "playlist-live", KEY = "network/" + LINK;
    // Outside the spawn-ticket area; 128-block separation proves one shared network cursor.
    private static final BlockPos A = new BlockPos(2048, 128, 0), B = new BlockPos(2176, 128, 0);
    private static final List<BlockPos> POSITIONS = List.of(A, B);
    private static String current = "";
    private static JsonObject active, fixture;
    private static PlaybackSnapshot before;
    private static long started, commandId;
    private static boolean bootWritten, complete;
    private static ServerPlayer lastPlayer;
    private static int connection;

    public static void tick(MinecraftServer server) {
        try {
            ScreenPlaylistService service = ScreenPlaylistRuntime.service();
            if (service == null) return;
            if (!bootWritten) {
                JsonObject boot = state(service.snapshot(KEY));
                boot.addProperty("boot", BOOT);
                boot.addProperty("serverPid", ProcessHandle.current().pid());
                write("boot-" + BOOT + ".json", boot);
                write("boot.json", boot);
                bootWritten = true;
            }
            if (server.getPlayerList().getPlayers().isEmpty()) { lastPlayer = null; return; }
            ServerPlayer player = server.getPlayerList().getPlayers().get(0);
            if (player != lastPlayer) { lastPlayer = player; connection++; }
            if (!Files.exists(DIR.resolve("request.json"))) return;
            JsonObject request = JsonParser.parseString(Files.readString(DIR.resolve("request.json"))).getAsJsonObject();
            String id = request.get("id").getAsString();
            if (!id.equals(current)) {
                current = id; active = request; complete = false; started = System.nanoTime();
                before = service.snapshot(KEY);
                if (fixture == null && Files.exists(DIR.resolve("fixture.json")))
                    fixture = JsonParser.parseString(Files.readString(DIR.resolve("fixture.json"))).getAsJsonObject();
                start(server, player, request.get("action").getAsString());
            }
            if (!complete) check(server, player);
        } catch (Throwable failure) { fail(failure); }
    }

    private static void start(MinecraftServer server, ServerPlayer player, String action) throws Exception {
        ServerLevel level = server.overworld();
        Config.SCREEN_TICK_RATE = 72000; Config.VIEW_DISTANCE = 512;
        player.setGameMode(GameType.CREATIVE);
        player.getAbilities().mayfly = true; player.getAbilities().flying = true; player.onUpdateAbilities();
        switch (action) {
            case "SETUP" -> {
                if (fixture != null) throw new IllegalStateException("SETUP requires a fresh runtime world");
                server.getPlayerList().setViewDistance(16); teleport(player, level, A.getX());
                fixture = new JsonObject();
                fixture.addProperty("owner", player.getUUID().toString());
                for (String name : List.of("a", "b", "c", "x")) {
                    UUID image = ServerImageManager.saveImage(server, "playlist-" + name + ".png",
                            Files.readAllBytes(DIR.resolve("fixture-" + name + ".png")), "image/png", player.getUUID().toString());
                    require(image != null, "Image pipeline rejected fixture " + name);
                    fixture.addProperty(name, image.toString());
                }
                for (BlockPos pos : POSITIONS) {
                    level.getChunk(pos);
                    level.setBlockAndUpdate(pos, BlockRegistries.SCREEN.get().defaultBlockState().setValue(ScreenBlock.FACING, Direction.NORTH));
                    ScreenBlockEntity screen = screen(level, pos);
                    screen.claimPlaced(player.getUUID());
                    require(ScreenAccessService.relink(player, screen, LINK), "Fixture relink rejected");
                }
                require(ScreenAccessService.select(player, screen(level, A), image("a")), "Fixture static image rejected");
                ScreenPlaylistService service = ScreenPlaylistRuntime.service();
                UUID list = service.create(player.getUUID(), "Live verification", service.library(player.getUUID()).revision());
                require(list != null, "Cannot create fixture playlist"); fixture.addProperty("playlist", list.toString());
                require(service.replaceSlides(player.getUUID(), list, List.of(new PlaylistSlide(image("a"), 10_000),
                        new PlaylistSlide(image("b"), 10_000), new PlaylistSlide(image("c"), 10_000)),
                        service.library(player.getUUID()).revision()), "Cannot fill fixture playlist");
                write("fixture.json", fixture);
            }
            case "PLAY_PAUSED" -> { control(player, "PLAY", null, "", 0); control(player, "PAUSE", null, "", 0); }
            case "NATURAL", "RESUME_FOR" -> control(player, "RESUME", null, "", 0);
            case "RESTART", "PREVIOUS", "NEXT", "PAUSE", "RESUME", "STOP", "CLEAR_QUEUE" -> control(player, action, null, "", 0);
            case "QUEUE_NEXT" -> control(player, "QUEUE_NEXT", image("x"), "", 10_000);
            case "ANNOUNCE" -> control(player, "ANNOUNCE", image("x"), "", 3_000);
            case "AWAY" -> {
                control(player, "PLAY", null, "", 0); control(player, "REPEAT", null, "LIST", 0);
                before = ScreenPlaylistRuntime.service().snapshot(KEY);
                server.getPlayerList().setViewDistance(3); teleport(player, level, 4096);
            }
            case "RETURN", "RESTART_READ" -> {
                server.getPlayerList().setViewDistance(16); teleport(player, level, A.getX());
                for (BlockPos pos : POSITIONS) level.getChunk(pos);
                if (ScreenPlaylistRuntime.service().snapshot(KEY).playing()) control(player, "PAUSE", null, "", 0);
            }
            case "SAVE" -> {
                ScreenPlaylistRuntime.service().save();
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "save-all flush");
            }
            case "HOLD", "WAIT_ANNOUNCEMENT", "LATE_JOIN", "UI" -> { }
            default -> throw new IllegalArgumentException("Unknown test action " + action);
        }
    }

    private static void check(MinecraftServer server, ServerPlayer player) throws Exception {
        String action = active.get("action").getAsString();
        PlaybackSnapshot now = ScreenPlaylistRuntime.service().snapshot(KEY);
        long elapsed = (System.nanoTime() - started) / 1_000_000L;
        if (elapsed > 180_000) throw new IllegalStateException("Server scenario timeout: " + current + " / " + action);
        switch (action) {
            case "NATURAL" -> {
                if (Objects.equals(now.currentImage(), before.currentImage())) return;
                require(now.playing() && !now.queued() && !now.announcing(), "Natural transition took wrong path");
                control(player, "PAUSE", null, "", 0); now = ScreenPlaylistRuntime.service().snapshot(KEY);
            }
            case "RESUME_FOR" -> {
                if (elapsed < 600) return;
                require(now.playing() && Objects.equals(now.currentImage(), before.currentImage()), "Resume unexpectedly changed slide");
                control(player, "PAUSE", null, "", 0); now = ScreenPlaylistRuntime.service().snapshot(KEY);
                require(now.offsetMillis() > before.offsetMillis(), "Resume did not advance elapsed time");
            }
            case "HOLD" -> {
                require(now.status() == PlaybackSnapshot.Status.PAUSED && now.offsetMillis() == before.offsetMillis(), "Pause failed to hold exact offset");
                if (elapsed < 1_500) return;
            }
            case "WAIT_ANNOUNCEMENT" -> {
                if (now.announcing()) return;
                require(now.status() == PlaybackSnapshot.Status.PAUSED, "Announcement did not restore paused status");
                require(now.offsetMillis() == active.get("expectedOffset").getAsLong(), "Announcement did not restore exact offset");
            }
            case "AWAY" -> {
                if (elapsed < 1_500 || POSITIONS.stream().anyMatch(pos -> loaded(server.overworld(), pos))) return;
                require(now.playing(), "Unloaded network stopped ticking");
                require(now.revision() > before.revision() || now.offsetMillis() > before.offsetMillis(), "Unloaded network failed to advance");
            }
            default -> { }
        }
        if (active.has("expectedImage")) require(Objects.equals(now.currentImage(), image(active.get("expectedImage").getAsString())), "Unexpected current image");
        if (active.has("expectedStatus")) require(now.status().name().equals(active.get("expectedStatus").getAsString()), "Unexpected playback status");
        if (active.has("expectedOffsetExact")) require(now.offsetMillis() == active.get("expectedOffsetExact").getAsLong(), "Unexpected exact offset");
        if (!"AWAY".equals(action)) {
            for (BlockPos pos : POSITIONS) {
                ScreenBlockEntity screen = screen(server.overworld(), pos);
                UUID expected = "SETUP".equals(action) ? image("a") : now.currentImage();
                require(Objects.equals(expected, screen.getResolvedImageId()), "Linked server display diverged at " + pos);
            }
        }
        JsonObject receipt = state(now);
        receipt.addProperty("id", current); receipt.addProperty("action", action); receipt.addProperty("boot", BOOT);
        receipt.addProperty("connection", connection); receipt.addProperty("serverElapsedMillis", elapsed);
        receipt.addProperty("beforeOffset", before.offsetMillis()); receipt.addProperty("beforeRevision", before.revision());
        receipt.addProperty("controlPath", "ScreenPlaylistRuntime.handle");
        receipt.addProperty("unloaded", "AWAY".equals(action));
        receipt.addProperty("serverChunksUnloaded", POSITIONS.stream().noneMatch(pos -> loaded(server.overworld(), pos)));
        receipt.add("positions", JSON.toJsonTree(new int[][]{{A.getX(), A.getY(), A.getZ()}, {B.getX(), B.getY(), B.getZ()}}));
        receipt.addProperty("link", LINK);
        if ("SETUP".equals(action)) receipt.addProperty("image", image("a").toString());
        write("server-" + current + ".json", receipt); write("server-ready.json", receipt); complete = true;
    }

    private static void control(ServerPlayer player, String action, UUID image, String value, long duration) {
        ScreenPlaylistService service = ScreenPlaylistRuntime.service();
        ScreenBlockEntity screen = screen((ServerLevel) player.level(), A);
        long beforeRevision = service.snapshot(KEY).revision();
        PlaylistWire.Request request = new PlaylistWire.Request(KEY, ScreenAccessService.state(screen).revision(),
                service.library(player.getUUID()).revision(), beforeRevision, ++commandId, action,
                UUID.fromString(fixture.get("playlist").getAsString()), image, 0, 0, duration, value);
        ScreenPlaylistRuntime.handle(player, A, request.json());
        require(service.snapshot(KEY).revision() > beforeRevision, "Native command rejected or no-op: " + action);
    }
    private static void teleport(ServerPlayer player, ServerLevel level, double x) {
        double y = 128.0, z = -3.0;
        __TELEPORT__
    }
    private static boolean loaded(ServerLevel level, BlockPos pos) { return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null; }
    private static ScreenBlockEntity screen(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof ScreenBlockEntity screen)) throw new IllegalStateException("Missing screen at " + pos);
        return screen;
    }
    private static UUID image(String name) { return UUID.fromString(fixture.get(name).getAsString()); }
    private static JsonObject state(PlaybackSnapshot state) {
        JsonObject result = new JsonObject(); result.addProperty("status", state.status().name());
        if (state.currentImage() == null) result.add("image", JsonNull.INSTANCE); else result.addProperty("image", state.currentImage().toString());
        result.addProperty("offset", state.offsetMillis()); result.addProperty("revision", state.revision());
        result.addProperty("announcing", state.announcing()); result.addProperty("queued", state.queued());
        result.addProperty("queueSize", state.queue().size()); result.addProperty("repeat", state.repeat().name());
        if (state.sourceId() == null) result.add("playlist", JsonNull.INSTANCE); else result.addProperty("playlist", state.sourceId().toString());
        return result;
    }
    public static void beforeShutdown(ScreenPlaylistService service) {
        if (service == null) return;
        try {
            service.save(); JsonObject receipt = state(service.snapshot(KEY)); receipt.addProperty("boot", BOOT);
            write("shutdown-" + BOOT + ".json", receipt); write("shutdown.json", receipt);
        } catch (Throwable failure) { fail(failure); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static void write(String name, JsonObject value) throws Exception {
        Path tmp = DIR.resolve(name + ".tmp"); Files.writeString(tmp, JSON.toJson(value));
        Files.move(tmp, DIR.resolve(name), StandardCopyOption.REPLACE_EXISTING);
    }
    private static void fail(Throwable failure) {
        try { Files.writeString(DIR.resolve("server-fail.txt"), current + ": " + failure); } catch (Exception ignored) { }
    }
}
