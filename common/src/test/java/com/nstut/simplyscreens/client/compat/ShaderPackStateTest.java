package com.nstut.simplyscreens.client.compat;

import net.irisshaders.iris.api.v0.IrisApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShaderPackStateTest {
    @AfterEach void reset() { IrisApi.enabled = IrisApi.shadow = IrisApi.fail = false; }
    @Test void absentShaderModIsANoOp() {
        assertFalse(ShaderPackState.discover(new ClassLoader(null) {}).isColorRestorePassNeeded());
    }
    @Test void onlyAnEnabledMainPassIsCaptured() {
        ShaderPackState state = ShaderPackState.discover(getClass().getClassLoader());
        assertFalse(state.isColorRestorePassNeeded());
        IrisApi.enabled = true;
        assertTrue(state.isColorRestorePassNeeded());
        IrisApi.shadow = true;
        assertFalse(state.isColorRestorePassNeeded());
        IrisApi.shadow = false;
        assertTrue(state.isColorRestorePassNeeded());
        IrisApi.enabled = false; // Shader toggle/reload is observed without restarting the game.
        assertFalse(state.isColorRestorePassNeeded());
    }
    @Test void brokenOptionalApiFailsClosed() {
        ShaderPackState state = ShaderPackState.discover(getClass().getClassLoader());
        IrisApi.enabled = IrisApi.fail = true;
        assertFalse(state.isColorRestorePassNeeded());
    }
    @Test void incompatibleApiShapeFailsClosed() {
        ClassLoader wrongApi = new ClassLoader(null) {
            @Override public Class<?> loadClass(String name) { return String.class; }
        };
        assertFalse(ShaderPackState.discover(wrongApi).isColorRestorePassNeeded());
    }
}
