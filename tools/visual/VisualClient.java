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
    private static final double CAMERA_POSITION_EPSILON = 0.01;
    private static final float CAMERA_ANGLE_EPSILON = 0.005f;
    private static String current = "";
    private static int ticks, sample, stableFrames, frameSubmissions;
    private static long submissions;
    private static boolean frameUnexpectedSubmission, frameSingleScreen, frameTileScreen;
    private static final java.util.Set<Long> frameTileOwners = new java.util.HashSet<>();
    private static JsonObject scene;
    private static java.util.concurrent.CompletableFuture<Void> reload;
    private static boolean reloaded, sampleArmed;
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
        if (!submissionMatchesScene(facing, width, height, anchorX, anchorY, anchorZ)
                || !ownerBelongsToScene(ownerX, ownerY, ownerZ)) {
            frameUnexpectedSubmission = true;
            return;
        }
        frameTileOwners.add(new BlockPos(ownerX, ownerY, ownerZ).asLong());
    }

    /** Records a single-quad logical-screen submission and proves it belongs to this fixture. */
    public static void submittedScreen(Direction facing, int width, int height,
                                       int anchorX, int anchorY, int anchorZ) {
        submissions++;
        frameSubmissions++;
        frameSingleScreen = true;
        if (!submissionMatchesScene(facing, width, height, anchorX, anchorY, anchorZ)) {
            frameUnexpectedSubmission = true;
        }
    }

    /** Client-tick phase: acknowledge the complete fixture and request the next camera sample. */
    public static boolean tick(Minecraft mc) {
        try {
            if (Files.exists(DIR.resolve("finish.txt"))) return true;
            if (mc.player == null || mc.level == null || !Files.exists(DIR.resolve("server-ready.json"))) return false;
            JsonObject ready = JsonParser.parseString(Files.readString(DIR.resolve("server-ready.json"))).getAsJsonObject();
            if (!ready.get("id").getAsString().equals(current)) {
                if (capturing.get()) return false;
                current = ready.get("id").getAsString();
                scene = ready;
                ticks = 0;
                sample = 0;
                resetStability();
                reload = null;
                reloaded = false;
            }
            mc.options.hideGui = true;
            mc.options.bobView().set(false);
            mc.options.fov().set(70);
            // Spectator flight otherwise expands the effective FOV (70 -> 77),
            // invalidating independently projected masks even when standing still.
            mc.options.fovEffectScale().set(0.0);
            Config.VIEW_DISTANCE = 512;
            if (mc.getWindow().getWidth() != VIEWPORT_WIDTH || mc.getWindow().getHeight() != VIEWPORT_HEIGHT) {
                mc.getWindow().setWindowed(VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
                resetStability();
                return false;
            }
            int totalSamples = scene.has("reload") ? BASE_SAMPLES * 2 : BASE_SAMPLES;
            if (++ticks > 1200 && sample < totalSamples) throw new IllegalStateException("fixture/sample deadline exceeded: " + current);
            if (scene.has("reload") && sample == BASE_SAMPLES && !reloaded && !capturing.get()) {
                if (reload == null) reload = mc.reloadResourcePacks();
                if (!reload.isDone()) return false;
                reload.join(); // Failure is fatal, never treated as a completed reload.
                reloaded = true;
                ticks = 0;
                resetStability();
                return false;
            }
            if (!(mc.level.getBlockEntity(new BlockPos(0,128,0)) instanceof ScreenBlockEntity screen)) {
                resetStability();
                return false;
            }
            UUID image = UUID.fromString(scene.get("image").getAsString());
            if (!image.equals(screen.getResolvedImageId()) || ClientImageManager.getTextureLocation(image) == null) {
                resetStability();
                return false;
            }
            // A ready JSON is emitted in the same server tick as the block updates. On a
            // remote client those packets can still be in flight. Do not arm sample 0
            // until every cell carries the new logical screen state.
            if (!sceneSynchronized(mc, image)) {
                resetStability();
                return false;
            }
            JsonArray expectedEye = scene.getAsJsonArray("eye");
            Vec3 actualEye = mc.player.getEyePosition();
            Vec3 expected = new Vec3(expectedEye.get(0).getAsDouble(), expectedEye.get(1).getAsDouble(), expectedEye.get(2).getAsDouble());
            if (actualEye.distanceTo(expected) > 0.05) {
                resetStability();
                return false;
            }
            if (sample >= totalSamples || capturing.get()) return false;
            // Four deterministic sub-degree offsets are the four unique raster alignments.
            float yaw = requestedYaw();
            mc.player.setYRot(yaw);
            mc.player.setXRot(scene.get("pitch").getAsFloat());
            if (mc.screen != null || mc.getOverlay() != null) {
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
            if (stableFrames < REQUIRED_STABLE_FRAMES) return;

            sampleArmed = false;
            clearObservedStability();
            capturing.set(true);
            int index = sample++;
            JsonObject frame = scene.deepCopy();
            frame.add("camera", JSON.toJsonTree(new double[]{cameraPosition.x, cameraPosition.y, cameraPosition.z}));
            frame.addProperty("cameraYaw", cameraYaw);
            frame.addProperty("cameraPitch", cameraPitch);
            frame.addProperty("fov", 70);
            frame.addProperty("sample", index);
            frame.addProperty("reloaded", reloaded);
            frame.addProperty("submissions", submissions);
            frame.addProperty("stableRenderFrames", REQUIRED_STABLE_FRAMES);
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
                && anchorX == 0 && anchorY == 128 && anchorZ == 0;
    }

    private static boolean ownerBelongsToScene(int ownerX, int ownerY, int ownerZ) {
        if (scene == null) return false;
        BlockPos anchor = new BlockPos(0, 128, 0);
        Direction facing = Direction.valueOf(scene.get("facing").getAsString());
        Direction width = switch (facing) {
            case NORTH, UP, DOWN -> Direction.WEST;
            case SOUTH -> Direction.EAST;
            case WEST -> Direction.SOUTH;
            case EAST -> Direction.NORTH;
        };
        Direction height = facing.getAxis().isHorizontal() ? Direction.UP
                : facing == Direction.UP ? Direction.SOUTH : Direction.NORTH;
        int size = scene.get("size").getAsInt();
        BlockPos owner = new BlockPos(ownerX, ownerY, ownerZ);
        for (int x = 0; x < size; x++) for (int y = 0; y < size; y++) {
            if (owner.equals(anchor.relative(width, x).relative(height, y))) return true;
        }
        return false;
    }

    private static float requestedYaw() {
        return scene.get("yaw").getAsFloat() + (sample % BASE_SAMPLES - (BASE_SAMPLES - 1) / 2.0f) * 0.025f;
    }

    private static boolean sceneSynchronized(Minecraft mc, UUID image) {
        BlockPos anchor = new BlockPos(0, 128, 0);
        Direction facing = Direction.valueOf(scene.get("facing").getAsString());
        Direction width = switch (facing) {
            case NORTH, UP, DOWN -> Direction.WEST;
            case SOUTH -> Direction.EAST;
            case WEST -> Direction.SOUTH;
            case EAST -> Direction.NORTH;
        };
        Direction height = facing.getAxis().isHorizontal() ? Direction.UP
                : facing == Direction.UP ? Direction.SOUTH : Direction.NORTH;
        int size = scene.get("size").getAsInt();
        for (int x = 0; x < size; x++) for (int y = 0; y < size; y++) {
            BlockPos pos = anchor.relative(width, x).relative(height, y);
            if (!(mc.level.getBlockEntity(pos) instanceof ScreenBlockEntity cell)) return false;
            if (!image.equals(cell.getResolvedImageId())
                    || !anchor.equals(cell.getAnchorPos())
                    || cell.getScreenWidth() != size
                    || cell.getScreenHeight() != size
                    || !cell.getBlockState().hasProperty(ScreenBlock.FACING)
                    || cell.getBlockState().getValue(ScreenBlock.FACING) != facing) return false;
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
