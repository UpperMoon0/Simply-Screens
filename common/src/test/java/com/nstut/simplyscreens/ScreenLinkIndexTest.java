package com.nstut.simplyscreens;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScreenLinkIndexTest {
    @Test
    void registrationUpdateLookupAndClearAreSharedAcrossVersions() {
        ScreenLinkIndex<String, Integer> index = new ScreenLinkIndex<>();
        index.register("overworld", 1, "lobby");
        index.register("overworld", 2, "lobby");
        assertEquals(2, index.getPositions("overworld", "lobby").size());

        index.update("overworld", 1, "spawn");
        assertEquals("spawn", index.getScreenId("overworld", 1));
        assertEquals(java.util.List.of(2), index.getPositions("overworld", "lobby"));

        index.clear("overworld");
        assertNull(index.getScreenId("overworld", 1));
    }
}
