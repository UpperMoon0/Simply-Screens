package com.nstut.simplyscreens.client.compat;

import java.lang.reflect.Method;

/** Optional Iris API shared by Iris and Oculus; no shader mod is a runtime dependency. */
public final class ShaderPackState {
    private static final ShaderPackState INSTANCE = discover(ShaderPackState.class.getClassLoader());
    private final Object api;
    private final Method enabled;
    private final Method shadow;

    private ShaderPackState(Object api, Method enabled, Method shadow) {
        this.api = api;
        this.enabled = enabled;
        this.shadow = shadow;
    }

    public static ShaderPackState discover(ClassLoader loader) {
        try {
            Class<?> type = Class.forName("net.irisshaders.iris.api.v0.IrisApi", false, loader);
            Object api = type.getMethod("getInstance").invoke(null);
            return new ShaderPackState(api, type.getMethod("isShaderPackInUse"),
                    type.getMethod("isRenderingShadowPass"));
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return new ShaderPackState(null, null, null);
        }
    }

    /** Fail closed if an incompatible API is installed; never replay shadow-map submissions. */
    public boolean isColorRestorePassNeeded() {
        if (api == null) return false;
        try {
            return Boolean.TRUE.equals(enabled.invoke(api)) && !Boolean.TRUE.equals(shadow.invoke(api));
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return false;
        }
    }

    public static boolean isRenderingShadowPass() {
        if (INSTANCE.api == null) return false;
        try { return Boolean.TRUE.equals(INSTANCE.shadow.invoke(INSTANCE.api)); }
        catch (ReflectiveOperationException | LinkageError unavailable) { return false; }
    }

    public static boolean shouldCapture() {
        return INSTANCE.isColorRestorePassNeeded();
    }
}
