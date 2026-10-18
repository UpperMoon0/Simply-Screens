package com.nstut.simplyscreens.testing.playlist;

import com.google.gson.*;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.client.screens.ImageLoadScreen;
import com.nstut.simplyscreens.helpers.ClientImageManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Observes actual remote-client block entities and image textures; never assigns test image state. */
public final class PlaylistClient {
    private static final Path DIR = Path.of(System.getenv("SS_PLAYLIST_DIR"));
    private static final Gson JSON = new Gson();
    private static final String CLIENT = UUID.randomUUID().toString();
    private static final AtomicBoolean capturing = new AtomicBoolean();
    private static String current = "";
    private static int ticks, stable, uiStep, uiTicks;
    private static boolean complete;
    private static ImageLoadScreen ui;
    private static JsonObject ready;
    private static final List<String> screenshots = new ArrayList<>();

    public static boolean tick(Minecraft mc) {
        try {
            if (Files.exists(DIR.resolve("finish.txt"))) return true;
            if (mc.player == null || mc.level == null || !Files.exists(DIR.resolve("server-ready.json"))
                    || !Files.exists(DIR.resolve("request.json"))) return false;
            JsonObject next = JsonParser.parseString(Files.readString(DIR.resolve("server-ready.json"))).getAsJsonObject();
            JsonObject request = JsonParser.parseString(Files.readString(DIR.resolve("request.json"))).getAsJsonObject();
            if (!next.get("id").getAsString().equals(request.get("id").getAsString())) return false;
            if (!next.get("id").getAsString().equals(current)) {
                current = next.get("id").getAsString(); ready = next;
                ticks = stable = uiStep = uiTicks = 0; complete = false; ui = null; screenshots.clear();
            }
            if (complete) return false;
            if (++ticks > 3_600) throw new IllegalStateException("Client scenario timeout: " + current);
            boolean unloaded = ready.get("unloaded").getAsBoolean();
            UUID image = ready.get("image").isJsonNull() ? null : UUID.fromString(ready.get("image").getAsString());
            for (JsonElement element : ready.getAsJsonArray("positions")) {
                JsonArray values = element.getAsJsonArray();
                BlockPos pos = new BlockPos(values.get(0).getAsInt(), values.get(1).getAsInt(), values.get(2).getAsInt());
                if (unloaded) {
                    if (loaded(mc, pos)) { stable = 0; return false; }
                } else {
                    if (!loaded(mc, pos) || !(mc.level.getBlockEntity(pos) instanceof ScreenBlockEntity screen)
                            || !Objects.equals(image, screen.getImageId())
                            || !ready.get("link").getAsString().equals(screen.getScreenId())) { stable = 0; return false; }
                }
            }
            if (!unloaded && image != null && ClientImageManager.getTextureLocation(image) == null) { stable = 0; return false; }
            if (++stable < 3) return false;
            if ("UI".equals(ready.get("action").getAsString()) && !exerciseUi(mc)) return false;
            JsonObject receipt = ready.deepCopy();
            receipt.addProperty("client", CLIENT); receipt.addProperty("observedScreens", unloaded ? 0 : 2);
            receipt.addProperty("clientChunksUnloaded", unloaded); receipt.addProperty("textureReady", !unloaded && image != null);
            receipt.addProperty("stableClientTicks", stable); receipt.addProperty("clientTicks", ticks);
            receipt.add("screenshots", JSON.toJsonTree(screenshots));
            write("client-" + current + ".json", receipt); complete = true;
        } catch (Throwable failure) { fail(failure); }
        return false;
    }

    private static boolean exerciseUi(Minecraft mc) throws Exception {
        if (ui == null) {
            JsonArray first = ready.getAsJsonArray("positions").get(0).getAsJsonArray();
            ui = new ImageLoadScreen(new BlockPos(first.get(0).getAsInt(), first.get(1).getAsInt(), first.get(2).getAsInt()));
            mc.setScreen(ui); return false;
        }
        int[] counts = ui.playlistProbeCounts();
        if (counts[0] < 1 || counts[1] < 3 || counts[2] < 1 || counts[3] < 4) return false;
        if (uiStep >= 6) {
            mc.setScreen(null); mc.getWindow().setWindowed(960, 720); return true;
        }
        if (capturing.get()) return false;
        if (uiTicks == 0) {
            boolean narrow = uiStep >= 3;
            mc.getWindow().setWindowed(narrow ? 360 : 960, narrow ? 280 : 720);
            ui.resize(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
            ui.smokeSelectTab(uiStep % 3);
        }
        if (++uiTicks < 25) return false;
        String stem = current + "-" + List.of("gallery", "playlist", "queue").get(uiStep % 3) + (uiStep >= 3 ? "-narrow" : "-normal");
        capturing.set(true);
        __CAPTURE__
        return false;
    }
    private static void save(com.mojang.blaze3d.platform.NativeImage pixels, String stem) {
        try (pixels) {
            pixels.writeToFile(DIR.resolve(stem + ".png")); screenshots.add(stem + ".png"); uiStep++; uiTicks = 0;
        } catch (Throwable failure) { fail(failure); }
        finally { capturing.set(false); }
    }
    private static boolean loaded(Minecraft mc, BlockPos pos) { return __CHUNK_LOADED__; }
    private static void write(String name, JsonObject value) throws Exception {
        Path tmp = DIR.resolve(name + ".tmp"); Files.writeString(tmp, JSON.toJson(value));
        Files.move(tmp, DIR.resolve(name), StandardCopyOption.REPLACE_EXISTING);
    }
    private static void fail(Throwable failure) {
        try { Files.writeString(DIR.resolve("client-fail.txt"), current + ": " + failure); } catch (Exception ignored) { }
    }
}
