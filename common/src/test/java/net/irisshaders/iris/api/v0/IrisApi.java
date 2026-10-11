package net.irisshaders.iris.api.v0;

/** Test fixture only. Never packaged in production. */
public final class IrisApi {
    private static final IrisApi INSTANCE = new IrisApi();
    public static boolean enabled, shadow, fail;
    public static IrisApi getInstance() { return INSTANCE; }
    public boolean isShaderPackInUse() {
        if (fail) throw new IllegalStateException("simulated API failure");
        return enabled;
    }
    public boolean isRenderingShadowPass() { return shadow; }
}
