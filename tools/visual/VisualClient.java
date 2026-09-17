package com.nstut.simplyscreens.testing.visual;

import com.google.gson.*;
import com.nstut.simplyscreens.Config;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.helpers.ClientImageManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Samples each unique raster alignment after a real renderer submission. */
public final class VisualClient {
    private static final Path DIR = Path.of(System.getenv("SS_VISUAL_DIR"));
    private static final Gson JSON = new Gson();
    private static final AtomicBoolean capturing = new AtomicBoolean();
    private static final int VIEWPORT_WIDTH = 960;
    private static final int VIEWPORT_HEIGHT = 720;
    private static final int BASE_SAMPLES = __BASE_SAMPLES__;
    private static final int REQUIRED_STABLE_SUBMISSIONS = 3;
    private static final double CAMERA_POSITION_EPSILON = 0.01;
    private static final float CAMERA_ANGLE_EPSILON = 0.005f;
    private static String current = "";
    private static int ticks, sample, stableSubmissions;
    private static long submissions, lastSubmission;
    private static JsonObject scene;
    private static java.util.concurrent.CompletableFuture<Void> reload;
    private static boolean reloaded, sampleArmed;
    private static Vec3 stableCameraPosition;
    private static float stableCameraYaw, stableCameraPitch;
    public static void submitted() { submissions++; }

    public static boolean tick(Minecraft mc) {
        try {
            if (Files.exists(DIR.resolve("finish.txt"))) return true;
            if (mc.player == null || mc.level == null || !Files.exists(DIR.resolve("server-ready.json"))) return false;
            JsonObject ready = JsonParser.parseString(Files.readString(DIR.resolve("server-ready.json"))).getAsJsonObject();
            if (!ready.get("id").getAsString().equals(current)) {
                if (capturing.get()) return false;
                current = ready.get("id").getAsString(); scene = ready;
                ticks = 0; sample = 0; resetStability();
                reload = null; reloaded = false;
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
            if (!(mc.level.getBlockEntity(new BlockPos(0,128,0)) instanceof ScreenBlockEntity screen)) return false;
            UUID image = UUID.fromString(scene.get("image").getAsString());
            if (!image.equals(screen.getResolvedImageId()) || ClientImageManager.getTextureLocation(image) == null) return false;
            JsonArray expectedEye = scene.getAsJsonArray("eye");
            Vec3 actualEye = mc.player.getEyePosition();
            Vec3 expected = new Vec3(expectedEye.get(0).getAsDouble(), expectedEye.get(1).getAsDouble(), expectedEye.get(2).getAsDouble());
            if (actualEye.distanceTo(expected) > 0.05) return false;
            if (sample >= totalSamples || capturing.get()) return false;
            // Four deterministic sub-degree offsets are the four unique raster alignments.
            float yaw = scene.get("yaw").getAsFloat() + (sample % BASE_SAMPLES - (BASE_SAMPLES - 1) / 2.0f) * 0.025f;
            mc.player.setYRot(yaw);
            mc.player.setXRot(scene.get("pitch").getAsFloat());
            if (mc.screen != null || mc.getOverlay() != null) {
                resetStability();
                return false;
            }
            // Camera rotation is applied during the client tick, while the renderer can
            // still expose the previous/interpolated camera for the next submission.
            // Require three consecutive rendered observations that both match the
            // requested sample and remain stable before admitting a screenshot.
            if (!sampleArmed) {
                sampleArmed = true;
                stableSubmissions = 0;
                stableCameraPosition = null;
                lastSubmission = submissions;
                return false;
            }
            if (submissions <= lastSubmission) return false;
            lastSubmission = submissions;
            var camera = mc.gameRenderer.getMainCamera();
            Vec3 cameraPosition = __CAMERA_POSITION__;
            float cameraYaw = __CAMERA_YAW__;
            float cameraPitch = __CAMERA_PITCH__;
            if (!cameraMatches(cameraPosition, cameraYaw, cameraPitch, expected, yaw, scene.get("pitch").getAsFloat())) {
                stableSubmissions = 0;
                stableCameraPosition = null;
                return false;
            }
            if (stableCameraPosition != null && !cameraStable(cameraPosition, cameraYaw, cameraPitch)) {
                stableSubmissions = 0;
            }
            stableCameraPosition = cameraPosition;
            stableCameraYaw = cameraYaw;
            stableCameraPitch = cameraPitch;
            stableSubmissions++;
            if (stableSubmissions < REQUIRED_STABLE_SUBMISSIONS) return false;
            sampleArmed = false;
            stableSubmissions = 0;
            stableCameraPosition = null;
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
            frame.addProperty("graphicsVendor", org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR));
            frame.addProperty("graphicsRenderer", org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER));
            frame.addProperty("graphicsVersion", org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION));
            String stem = current + "-" + String.format("%02d", index);
            __CAPTURE__
            return false;
        } catch (Throwable failure) {
            fail(failure);
            return false;
        }
    }

    private static void resetStability() {
        sampleArmed = false;
        stableSubmissions = 0;
        stableCameraPosition = null;
        lastSubmission = submissions;
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
