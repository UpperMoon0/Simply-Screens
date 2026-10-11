package com.nstut.simplyscreens.testing.visual;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4fc;

/** Test-only setup observations; missing version-specific APIs are explicit. */
public final class ShaderDiagnostics {
    private static final Gson JSON = new Gson();

    public static JsonObject capture(Minecraft mc) {
        JsonObject result = new JsonObject();
        result.add("renderModelView", JSON.toJsonTree(new org.joml.Matrix4f(RenderSystem.getModelViewMatrix()).get(new float[16])));
        read(result, "worldGameTime", mc.level, "getGameTime");
        read(result, "worldDayTime", mc.level, "getDayTime");
        try {
            Class<?> celestial = Class.forName("net.irisshaders.iris.uniforms.CelestialUniforms");
            result.add("sunAngle", JSON.toJsonTree(celestial.getMethod("getSunAngle").invoke(null)));
        } catch (ReflectiveOperationException | LinkageError missing) {
            result.addProperty("sunAngleUnavailable", missing.getClass().getSimpleName());
        }
        try {
            Class<?> type = Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");
            Object state = type.getField("INSTANCE").get(null);
            read(result, "irisTickDelta", state, "getTickDelta");
            read(result, "irisProjection", state, "getGbufferProjection");
            read(result, "irisModelView", state, "getGbufferModelView");
        } catch (ReflectiveOperationException | LinkageError missing) {
            result.addProperty("irisStateUnavailable", missing.getClass().getSimpleName());
        }
        return result;
    }

    private static void read(JsonObject result, String name, Object owner, String method) {
        try {
            Object value = owner.getClass().getMethod(method).invoke(owner);
            if (value instanceof Matrix4fc matrix) value = matrix.get(new float[16]);
            result.add(name, JSON.toJsonTree(value));
        } catch (ReflectiveOperationException | LinkageError missing) {
            result.addProperty(name + "Unavailable", missing.getClass().getSimpleName());
        }
    }
}
