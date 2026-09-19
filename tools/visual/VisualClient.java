package com.nstut.simplyscreens.testing.visual;

import com.google.gson.*;
import com.nstut.simplyscreens.Config;
import com.nstut.simplyscreens.blocks.ScreenBlock;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.helpers.ClientImageManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Samples each unique raster alignment from a completed world-render framebuffer. */
public final class VisualClient {
    private static final Path DIR = Path.of(System.getenv("SS_VISUAL_DIR"));
    private static final Gson JSON = new Gson();
    private static final AtomicBoolean capturing = new AtomicBoolean();
    private static final int VIEWPORT_WIDTH = 960;
    private static final int VIEWPORT_HEIGHT = 720;
    private static final int BASE_SAMPLES = __BASE_SAMPLES__;
    private static final int REQUIRED_STABLE_FRAMES = 3;
    private static final int REQUIRED_FIRST_SAMPLE_STABLE_FRAMES = 5;
    private static final double CAMERA_POSITION_EPSILON = 0.01;
    private static final float CAMERA_ANGLE_EPSILON = 0.005f;
    private static String current = "";
    private static String waitingFor = "scene";
    private static int ticks, sample, stableFrames, frameSubmissions;
    private static long submissions;
    private static boolean frameUnexpectedSubmission, frameSingleScreen, frameTileScreen;
    private static final java.util.Set<Long> frameTileOwners = new java.util.HashSet<>();
    private static JsonObject scene;
    private static java.util.concurrent.CompletableFuture<Void> reload;
    private static boolean reloaded, sampleArmed, terrainRebuilt;
    private static Vec3 stableCameraPosition;
    private static float stableCameraYaw, stableCameraPitch;

    /** Called at the first world-render stage, before block-entity geometry is submitted. */
    public static void beginRenderFrame() {
        frameSubmissions = 0;
        frameUnexpectedSubmission = false;
        frameSingleScreen = false;
        frameTileScreen = false;
        frameTileOwners.clear();
    }

    /** Records a legacy per-cell image submission and proves it belongs to this fixture. */
    public static void submittedTile(Direction facing, int width, int height,
                                     int anchorX, int anchorY, int anchorZ,
                                     int ownerX, int ownerY, int ownerZ) {
        submissions++;
        frameSubmissions++;
        frameTileScreen = true;
        BlockPos owner = new BlockPos(ownerX, ownerY, ownerZ);
        if (!submissionMatchesScene(facing, width, height, anchorX, anchorY, anchorZ)
                || !ownerBelongsToScene(ownerX, ownerY, ownerZ)
                || (anchorUnloadedScenario() && owner.equals(sceneAnchor()))) {
            frameUnexpectedSubmission = true;
            return;
        }
        frameTileOwners.add(owner.asLong());
    }

    /** Records a single-quad logical-screen submission and proves it belongs to this fixture. */
    public static void submittedScreen(Direction facing, int width, int height,
                                       int anchorX, int anchorY, int anchorZ,
                                       int ownerX, int ownerY, int ownerZ) {
        submissions++;
        frameSubmissions++;
        frameSingleScreen = true;
        BlockPos owner = new BlockPos(ownerX, ownerY, ownerZ);
        if (!submissionMatchesScene(facing, width, height, anchorX, anchorY, anchorZ)
                || !ownerBelongsToScene(ownerX, ownerY, ownerZ)
                || ((anchorUnloadedScenario() || anchorLoadedFallbackScenario()) && owner.equals(sceneAnchor()))) {
            frameUnexpectedSubmission = true;
        }
    }

    /** Test-only hook used by the instrumented renderer to emulate an anchor omitted this frame. */
    public static boolean skipAnchorOwner(boolean isAnchor) {
        return isAnchor && anchorLoadedFallbackScenario();
    }

    /** Client-tick phase: acknowledge the complete fixture and request the next camera sample. */
    public static boolean tick(Minecraft mc) {
        try {
            if (Files.exists(DIR.resolve("finish.txt"))) return true;
            if (mc.player == null || mc.level == null || !Files.exists(DIR.resolve("server-ready.json"))) return false;
            String readyJson;
            try {
                readyJson = Files.readString(DIR.resolve("server-ready.json"));
            } catch (NoSuchFileException transientSceneGap) {
                return false;
            }
            JsonObject ready = JsonParser.parseString(readyJson).getAsJsonObject();
            if (!ready.get("id").getAsString().equals(current)) {
                if (capturing.get()) return false;
                current = ready.get("id").getAsString();
                scene = ready;
                ticks = 0;
                sample = 0;
                resetStability();
                reload = null;
                reloaded = false;
                terrainRebuilt = false;
            }
            mc.options.hideGui = true;
            mc.options.bobView().set(false);
            mc.options.fov().set(70);
            // Spectator flight otherwise expands the effective FOV (70 -> 77),
            // invalidating independently projected masks even when standing still.
            mc.options.fovEffectScale().set(0.0);
            // The upward-facing fixture puts distant cameras above the cloud layer.
            // Clouds and their fog are unrelated occluders; only the explicit
            // foreground blocks belong to this depth-regression scene.
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            Config.VIEW_DISTANCE = 512;
            if (mc.getWindow().getWidth() != VIEWPORT_WIDTH || mc.getWindow().getHeight() != VIEWPORT_HEIGHT) {
                mc.getWindow().setWindowed(VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
                resetStability();
                return false;
            }
            int totalSamples = scene.has("reload") ? BASE_SAMPLES * 2 : BASE_SAMPLES;
            if (++ticks > 1200 && sample < totalSamples) throw new IllegalStateException(
                    "fixture/sample deadline exceeded: " + current + "; waiting for " + waitingFor
                            + "; sample=" + sample + "; eye=" + mc.player.getEyePosition());
            if (scene.has("reload") && sample == BASE_SAMPLES && !reloaded && !capturing.get()) {
                if (reload == null) reload = mc.reloadResourcePacks();
                if (!reload.isDone()) return false;
                reload.join(); // Failure is fatal, never treated as a completed reload.
                reloaded = true;
                terrainRebuilt = false;
                ticks = 0;
                resetStability();
                return false;
            }
            UUID image = UUID.fromString(scene.get("image").getAsString());
            if (ClientImageManager.getTextureLocation(image) == null) {
                waitingFor = "fixture texture";
                resetStability();
                return false;
            }
            // A ready JSON is emitted in the same server tick as the block updates. On a
            // remote client those packets can still be in flight. For the anchor-unloaded
            // regression, synchronization additionally proves the anchor chunk is gone
            // while at least one child chunk and its replicated logical-screen data remain.
            if (!sceneSynchronized(mc, image)) {
                waitingFor = anchorUnloadedScenario()
                        ? "anchor chunk unload with synchronized child cells"
                        : "all screen cells to synchronize";
                resetStability();
                return false;
            }
            // Block entities synchronize before the asynchronous terrain mesh does.
            // Discard the previous scene's compiled geometry once, then wait for the
            // new mesh before measuring pixels. A resource reload needs the same gate.
            if (!terrainRebuilt) {
                mc.levelRenderer.allChanged();
                terrainRebuilt = true;
                resetStability();
                return false;
            }
            JsonArray expectedEye = scene.getAsJsonArray("eye");
            Vec3 actualEye = mc.player.getEyePosition();
            Vec3 expected = new Vec3(expectedEye.get(0).getAsDouble(), expectedEye.get(1).getAsDouble(), expectedEye.get(2).getAsDouble());
            if (actualEye.distanceTo(expected) > 0.05) {
                waitingFor = "teleport to " + expected;
                resetStability();
                return false;
            }
            if (sample >= totalSamples || capturing.get()) return false;
            // Four deterministic sub-degree offsets are the four unique raster alignments.
            float yaw = requestedYaw();
            mc.player.setYRot(yaw);
            mc.player.setXRot(scene.get("pitch").getAsFloat());
            if (mc.screen != null || mc.getOverlay() != null) {
                waitingFor = "GUI/overlay to close (screen="
                        + (mc.screen == null ? "none" : mc.screen.getClass().getName())
                        + ", overlay=" + (mc.getOverlay() == null ? "none" : mc.getOverlay().getClass().getName()) + ")";
                resetStability();
                return false;
            }
            if (!sampleArmed) {
                sampleArmed = true;
                clearObservedStability();
            }
            return false;
        } catch (Throwable failure) {
            fail(failure);
            return false;
        }
    }

    /**
     * End-of-world-render phase. Renderer submission alone is insufficient: old
     * versions buffer vertices and modern versions queue custom geometry. At this
     * event the admitted geometry is actually present in the framebuffer we capture.
     */
    public static void afterRender(Minecraft mc) {
        try {
            if (!sampleArmed || capturing.get() || scene == null || mc.player == null || mc.level == null) return;
            if (!frameMatchesCurrentFixture()) {
                waitingFor = "matching renderer submissions (count=" + frameSubmissions
                        + ", unexpected=" + frameUnexpectedSubmission + ")";
                clearObservedStability();
                return;
            }
            if (!terrainReady(mc)) {
                waitingFor = "terrain rebuild";
                clearObservedStability();
                return;
            }
            JsonArray expectedEye = scene.getAsJsonArray("eye");
            Vec3 expected = new Vec3(expectedEye.get(0).getAsDouble(), expectedEye.get(1).getAsDouble(), expectedEye.get(2).getAsDouble());
            float yaw = requestedYaw();
            float pitch = scene.get("pitch").getAsFloat();
            var camera = mc.gameRenderer.getMainCamera();
            Vec3 cameraPosition = __CAMERA_POSITION__;
            float cameraYaw = __CAMERA_YAW__;
            float cameraPitch = __CAMERA_PITCH__;
            if (!cameraMatches(cameraPosition, cameraYaw, cameraPitch, expected, yaw, pitch)) {
                waitingFor = "camera alignment";
                clearObservedStability();
                return;
            }
            if (stableCameraPosition != null && !cameraStable(cameraPosition, cameraYaw, cameraPitch)) {
                clearObservedStability();
            }
            stableCameraPosition = cameraPosition;
            stableCameraYaw = cameraYaw;
            stableCameraPitch = cameraPitch;
            stableFrames++;
            int requiredStableFrames = sample % BASE_SAMPLES == 0
                    ? REQUIRED_FIRST_SAMPLE_STABLE_FRAMES : REQUIRED_STABLE_FRAMES;
            if (stableFrames < requiredStableFrames) return;

            sampleArmed = false;
            clearObservedStability();
            capturing.set(true);
            int index = sample++;
            JsonObject frame = scene.deepCopy();
            frame.add("camera", JSON.toJsonTree(new double[]{cameraPosition.x, cameraPosition.y, cameraPosition.z}));
            frame.addProperty("cameraYaw", cameraYaw);
            frame.addProperty("cameraPitch", cameraPitch);
            frame.addProperty("fov", 70);
            frame.addProperty("clouds", mc.options.cloudStatus().get().name());
            frame.addProperty("sample", index);
            frame.addProperty("reloaded", reloaded);
            frame.addProperty("submissions", submissions);
            frame.addProperty("stableRenderFrames", requiredStableFrames);
            if (anchorUnloadedScenario() || anchorLoadedFallbackScenario()) {
                frame.addProperty("anchorChunkLoaded", chunkLoaded(mc, sceneAnchor()));
                frame.addProperty("anchorEntityPresent", mc.level.getBlockEntity(sceneAnchor()) instanceof ScreenBlockEntity);
            }
            if (anchorUnloadedScenario()) frame.add("loadedCells", loadedScreenCells(mc));
            frame.addProperty("graphicsVendor", org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR));
            frame.addProperty("graphicsRenderer", org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER));
            frame.addProperty("graphicsVersion", org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION));
            String stem = current + "-" + String.format("%02d", index);
            __CAPTURE__
        } catch (Throwable failure) {
            fail(failure);
        }
    }


    private static boolean frameMatchesCurrentFixture() {
        if (scene == null || frameUnexpectedSubmission || frameSubmissions <= 0
                || (frameSingleScreen && frameTileScreen)) return false;
        int size = scene.get("size").getAsInt();
        if (frameSingleScreen) return frameSubmissions == 1;
        return frameTileScreen && !frameTileOwners.isEmpty();
    }

    private static boolean submissionMatchesScene(Direction facing, int width, int height,
                                                  int anchorX, int anchorY, int anchorZ) {
        return scene != null
                && facing == Direction.valueOf(scene.get("facing").getAsString())
                && width == scene.get("size").getAsInt()
                && height == scene.get("size").getAsInt()
                && new BlockPos(anchorX, anchorY, anchorZ).equals(sceneAnchor());
    }

    private static boolean ownerBelongsToScene(int ownerX, int ownerY, int ownerZ) {
        if (scene == null) return false;
        BlockPos anchor = sceneAnchor();
        Direction facing = Direction.valueOf(scene.get("facing").getAsString());
        Direction width = widthDirection(facing);
        Direction height = heightDirection(facing);
        int size = scene.get("size").getAsInt();
        BlockPos owner = new BlockPos(ownerX, ownerY, ownerZ);
        for (int x = 0; x < size; x++) for (int y = 0; y < size; y++) {
            if (owner.equals(anchor.relative(width, x).relative(height, y))) return true;
        }
        return false;
    }

    private static BlockPos sceneAnchor() {
        JsonArray anchor = scene.getAsJsonArray("anchor");
        return new BlockPos(anchor.get(0).getAsInt(), anchor.get(1).getAsInt(), anchor.get(2).getAsInt());
    }

    private static float requestedYaw() {
        return scene.get("yaw").getAsFloat() + (sample % BASE_SAMPLES - (BASE_SAMPLES - 1) / 2.0f) * 0.025f;
    }

    private static boolean sceneSynchronized(Minecraft mc, UUID image) {
        BlockPos anchor = sceneAnchor();
        if (anchorUnloadedScenario() && chunkLoaded(mc, anchor)) return false;
        Direction facing = Direction.valueOf(scene.get("facing").getAsString());
        Direction width = widthDirection(facing);
        Direction height = heightDirection(facing);
        int size = scene.get("size").getAsInt();
        int loadedCells = 0;
        for (int x = 0; x < size; x++) for (int y = 0; y < size; y++) {
            BlockPos pos = anchor.relative(width, x).relative(height, y);
            if (anchorUnloadedScenario() && !chunkLoaded(mc, pos)) continue;
            loadedCells++;
            if (!(mc.level.getBlockEntity(pos) instanceof ScreenBlockEntity cell)) return false;
            if (!image.equals(cell.getResolvedImageId())
                    || !anchor.equals(cell.getAnchorPos())
                    || cell.getScreenWidth() != size
                    || cell.getScreenHeight() != size
                    || !cell.getBlockState().hasProperty(ScreenBlock.FACING)
                    || cell.getBlockState().getValue(ScreenBlock.FACING) != facing) return false;
        }
        return !anchorUnloadedScenario() || loadedCells > 0;
    }

    private static JsonArray loadedScreenCells(Minecraft mc) {
        JsonArray cells = new JsonArray();
        BlockPos anchor = sceneAnchor();
        Direction facing = Direction.valueOf(scene.get("facing").getAsString());
        Direction width = widthDirection(facing);
        Direction height = heightDirection(facing);
        int size = scene.get("size").getAsInt();
        for (int x = 0; x < size; x++) for (int y = 0; y < size; y++) {
            BlockPos pos = anchor.relative(width, x).relative(height, y);
            if (!chunkLoaded(mc, pos) || !(mc.level.getBlockEntity(pos) instanceof ScreenBlockEntity)) continue;
            cells.add(JSON.toJsonTree(new int[]{x, y}));
        }
        return cells;
    }

    private static boolean chunkLoaded(Minecraft mc, BlockPos pos) {
        return __CHUNK_LOADED__;
    }

    private static boolean anchorUnloadedScenario() {
        return scene != null && scene.has("anchorUnloaded") && scene.get("anchorUnloaded").getAsBoolean();
    }

    private static boolean anchorLoadedFallbackScenario() {
        return scene != null && scene.has("anchorLoadedFallback")
                && scene.get("anchorLoadedFallback").getAsBoolean();
    }

    private static Direction widthDirection(Direction facing) {
        return switch (facing) {
            case NORTH, UP, DOWN -> Direction.WEST;
            case SOUTH -> Direction.EAST;
            case WEST -> Direction.SOUTH;
            case EAST -> Direction.NORTH;
        };
    }

    private static Direction heightDirection(Direction facing) {
        return facing.getAxis().isHorizontal() ? Direction.UP
                : facing == Direction.UP ? Direction.SOUTH : Direction.NORTH;
    }

    private static boolean terrainReady(Minecraft mc) {
        if (!__TERRAIN_IDLE__) return false;
        // In the anchor-unloaded regression, frameMatchesCurrentFixture() already
        // proves that a retained child from a renderable section submitted the logical
        // screen. Requiring every retained child section to be "visible" is invalid:
        // NF26's isSectionCompiledAndVisible() includes a frustum/visibility threshold,
        // so off-screen retained child sections can never satisfy it. The empty global
        // rebuild queue plus the observed child submission is the correct readiness gate.
        if (anchorUnloadedScenario()) return true;
        BlockPos anchor = sceneAnchor();
        Direction facing = Direction.valueOf(scene.get("facing").getAsString());
        // Include the neighboring section on each side for cross-chunk fixtures
        // without requiring unrelated, potentially occluded world sections.
        Direction width = widthDirection(facing);
        Direction height = heightDirection(facing);
        int size = scene.get("size").getAsInt();
        for (int x = 0; x < size; x++) for (int y = 0; y < size; y++) {
            BlockPos pos = anchor.relative(width, x).relative(height, y);
            if (!__SECTION_COMPILED__) return false;
            if (scene.get("occluded").getAsBoolean() && x < size / 2) {
                pos = pos.relative(facing);
                if (!__SECTION_COMPILED__) return false;
            }
        }
        return true;
    }

    private static void resetStability() {
        sampleArmed = false;
        frameSubmissions = 0;
        frameUnexpectedSubmission = false;
        frameSingleScreen = false;
        frameTileScreen = false;
        frameTileOwners.clear();
        clearObservedStability();
    }

    private static void clearObservedStability() {
        stableFrames = 0;
        stableCameraPosition = null;
    }

    private static boolean cameraMatches(Vec3 position, float yaw, float pitch, Vec3 expectedPosition, float expectedYaw, float expectedPitch) {
        return position.distanceTo(expectedPosition) <= CAMERA_POSITION_EPSILON
                && angleDistance(yaw, expectedYaw) <= CAMERA_ANGLE_EPSILON
                && angleDistance(pitch, expectedPitch) <= CAMERA_ANGLE_EPSILON;
    }

    private static boolean cameraStable(Vec3 position, float yaw, float pitch) {
        return position.distanceTo(stableCameraPosition) <= CAMERA_POSITION_EPSILON
                && angleDistance(yaw, stableCameraYaw) <= CAMERA_ANGLE_EPSILON
                && angleDistance(pitch, stableCameraPitch) <= CAMERA_ANGLE_EPSILON;
    }

    private static float angleDistance(float a, float b) {
        float delta = Math.abs((a - b) % 360.0f);
        return delta > 180.0f ? 360.0f - delta : delta;
    }

    private static void save(com.mojang.blaze3d.platform.NativeImage pixels, JsonObject frame, String stem) {
        try (pixels) {
            pixels.writeToFile(DIR.resolve(stem+".png"));
            Path tmp = DIR.resolve(stem+".json.tmp");
            Files.writeString(tmp, JSON.toJson(frame));
            Files.move(tmp, DIR.resolve(stem+".json"), StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable failure) { fail(failure); }
        finally { capturing.set(false); }
    }
    private static void fail(Throwable failure) {
        try { Files.writeString(DIR.resolve("client-fail.txt"), failure.toString()); } catch (Exception ignored) { }
    }
}
