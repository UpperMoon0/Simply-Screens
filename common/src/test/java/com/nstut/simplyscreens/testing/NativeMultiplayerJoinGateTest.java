package com.nstut.simplyscreens.testing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeMultiplayerJoinGateTest {
    @Test void resourceReloadMustCompleteBeforeTheSingleNativeJoin() {
        var gate = new NativeMultiplayerJoinGate();
        assertFalse(gate.claim(true, true, true));
        assertTrue(gate.claim(true, false, true));
        assertFalse(gate.claim(true, false, true));
    }
    @Test void nativeErrorOrOtherScreensAreNeverDismissed() {
        var gate = new NativeMultiplayerJoinGate();
        assertFalse(gate.claim(false, false, true));
        assertFalse(gate.claim(false, true, true));
        assertTrue(gate.claim(true, false, true));
    }
    @Test void falseMultiplayerEligibilityCannotStartAConnection() {
        var gate = new NativeMultiplayerJoinGate();
        assertFalse(gate.claim(true, false, false));
        assertFalse(gate.claim(true, true, false));
        assertTrue(gate.claim(true, false, true));
    }
}
