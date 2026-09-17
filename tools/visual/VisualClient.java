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

/** Samples a bounded window, never waits until a few lucky frames look good. */
public final class VisualClient {
    private static final Path DIR = Path.of(System.getenv("SS_VISUAL_DIR"));
    private static final Gson JSON = new Gson();
    private static final AtomicBoolean capturing = new AtomicBoolean();
    private static final int VIEWPORT_WIDTH = 960;
    private static final int VIEWPORT_HEIGHT = 720;
    private static String current = "";
    private static int ticks, sample;
    private static long submissions, lastSubmission;
    private static JsonObject scene;
    private static java.util.concurrent.CompletableFuture<Void> reload;
    private static boolean reloaded;
    public static void submitted() { submissions++; }

    public static boolean tick(Minecraft mc) {
        try {
            if (Files.exists(DIR.resolve("finish.txt"))) return true;
            if (mc.player == null || mc.level == null || !Files.exists(DIR.resolve("server-ready.json"))) return false;
            JsonObject ready = JsonParser.parseString(Files.readString(DIR.resolve("server-ready.json"))).getAsJsonObject();
            if (!ready.get("id").getAsString().equals(current)) {
                if (capturing.get()) return false;
                current = ready.get("id").getAsString(); scene = ready;
                ticks = 0; sample = 0; lastSubmission = submissions;
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
            if (++ticks > 1200 && sample < 16) throw new IllegalStateException("fixture/sample deadline exceeded: " + current);
            if (scene.has("reload") && sample == 8 && !reloaded && !capturing.get()) {
                if (reload == null) reload = mc.reloadResourcePacks();
                if (!reload.isDone()) return false;
                reload.join(); // Failure is fatal, never treated as a completed reload.
                reloaded = true;
                ticks = 0;
                return false;
            }
            if (!(mc.level.getBlockEntity(new BlockPos(0,128,0)) instanceof ScreenBlockEntity screen)) return false;
            UUID image = UUID.fromString(scene.get("image").getAsString());
            if (!image.equals(screen.getResolvedImageId()) || ClientImageManager.getTextureLocation(image) == null) return false;
            JsonArray expectedEye = scene.getAsJsonArray("eye");
            Vec3 actualEye = mc.player.getEyePosition();
            Vec3 expected = new Vec3(expectedEye.get(0).getAsDouble(), expectedEye.get(1).getAsDouble(), expectedEye.get(2).getAsDouble());
            if (actualEye.distanceTo(expected) > 0.05) return false;
            if (sample >= 16 || capturing.get()) return false;
            // A deterministic sub-degree sweep changes raster alignment without changing geometry.
            float yaw = scene.get("yaw").getAsFloat() + (sample % 4 - 1.5f) * 0.025f;
            mc.player.setYRot(yaw);
            mc.player.setXRot(scene.get("pitch").getAsFloat());
            if (ticks < 100 || ticks % 6 != 0 || mc.screen != null || mc.getOverlay() != null) return false;
            if (submissions <= lastSubmission) throw new IllegalStateException("no actual screen geometry submitted");
            lastSubmission = submissions;
            capturing.set(true);
            int index = sample++;
            JsonObject frame = scene.deepCopy();
            var camera = mc.gameRenderer.getMainCamera();
            Vec3 eye = __CAMERA_POSITION__;
            frame.add("camera", JSON.toJsonTree(new double[]{eye.x, eye.y, eye.z}));
            frame.addProperty("cameraYaw", __CAMERA_YAW__);
            frame.addProperty("cameraPitch", __CAMERA_PITCH__);
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
