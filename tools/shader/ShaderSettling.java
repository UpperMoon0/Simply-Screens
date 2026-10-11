package com.nstut.simplyscreens.testing.visual;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;

/** Test-only convergence probe; uses the same post-shader screenshot API as the oracle. */
public final class ShaderSettling {
    private static final Path DIR = Path.of(System.getenv("SS_VISUAL_DIR"));
    private static final int WINDOW = 10, COLUMNS = 16, ROWS = 12;
    private static final double LIMIT = 0.01;
    private static String current = "";
    private static long started, lastProbe;
    private static int count;
    private static volatile boolean pending, settled;
    private static final double[][] history = new double[WINDOW][];
    private static final double[] times = new double[WINDOW];

    public static boolean ready(Minecraft mc, String scene) {
        if (!scene.equals(current)) {
            if (pending) return false;
            current = scene; started = System.nanoTime(); lastProbe = 0;
            count = 0; settled = false;
            java.util.Arrays.fill(history, null);
        }
        if (settled) return true;
        long now = System.nanoTime();
        if (now - started > 120_000_000_000L) {
            throw new IllegalStateException("Shader framebuffer did not converge within 120 seconds: " + scene);
        }
        if (pending || now - lastProbe < 1_000_000_000L) return false;
        lastProbe = now; pending = true;
        __PROBE__
        return false;
    }

    private static void accept(NativeImage pixels) {
        try (pixels) {
            int slot = count % WINDOW;
            Path file = DIR.resolve(current + "-settle-" + slot + ".png");
            pixels.writeToFile(file);
            var image = ImageIO.read(file.toFile());
            if (image.getWidth() != 960 || image.getHeight() != 720) {
                throw new IllegalStateException("Unexpected shader settling framebuffer dimensions");
            }
            double[] means = new double[COLUMNS * ROWS * 3];
            for (int y = 0; y < 720; y++) for (int x = 0; x < 960; x++) {
                int offset = ((y / 60) * COLUMNS + x / 60) * 3;
                int rgb = image.getRGB(x, y);
                means[offset] += (rgb >>> 16) & 255;
                means[offset + 1] += (rgb >>> 8) & 255;
                means[offset + 2] += rgb & 255;
            }
            for (int i = 0; i < means.length; i++) means[i] /= 3600.0;
            history[slot] = means;
            times[slot] = (System.nanoTime() - started) / 1_000_000_000.0;
            count++;
            double range = Double.POSITIVE_INFINITY;
            if (count >= WINDOW) {
                range = 0;
                for (int i = 0; i < means.length; i++) {
                    double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
                    for (double[] sample : history) { low = Math.min(low, sample[i]); high = Math.max(high, sample[i]); }
                    range = Math.max(range, high - low);
                }
            }
            double elapsed = times[slot];
            boolean converged = count >= WINDOW && elapsed >= 20 && range <= LIMIT;
            JsonObject receipt = new JsonObject();
            receipt.addProperty("scene", current);
            receipt.addProperty("status", converged ? "pass" : "settling");
            receipt.addProperty("probeCount", count);
            receipt.addProperty("seconds", elapsed);
            if (Double.isFinite(range)) receipt.addProperty("maxTileChannelRange", range);
            JsonArray samples = new JsonArray();
            for (int i = 0; i < WINDOW; i++) if (history[i] != null) {
                JsonObject sample = new JsonObject();
                sample.addProperty("file", current + "-settle-" + i + ".png");
                sample.addProperty("seconds", times[i]);
                sample.add("means", new Gson().toJsonTree(history[i]));
                samples.add(sample);
            }
            receipt.add("samples", samples);
            Files.writeString(DIR.resolve(current + "-settling.json"), new Gson().toJson(receipt));
            settled = converged;
        } catch (Throwable failure) {
            try { Files.writeString(DIR.resolve("client-fail.txt"), failure.toString()); } catch (Exception ignored) { }
        } finally { lastProbe = System.nanoTime(); pending = false; }
    }
}
