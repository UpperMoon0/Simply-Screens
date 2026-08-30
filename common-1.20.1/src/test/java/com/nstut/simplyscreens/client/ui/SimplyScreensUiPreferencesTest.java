package com.nstut.simplyscreens.client.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimplyScreensUiPreferencesTest {
    @Test
    void missingFileDefaultsToDark(@TempDir Path directory) {
        assertEquals(UiThemeMode.DARK,
                SimplyScreensUiPreferences.getThemeMode(directory.resolve("missing.properties")));
    }

    @Test
    void themeRoundTripsAndPreservesOtherKeys(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("preferences.properties");
        Files.writeString(file, "other.setting=keep\nui.theme=dark\n");
        SimplyScreensUiPreferences.setThemeMode(file, UiThemeMode.LIGHT);
        assertEquals(UiThemeMode.LIGHT, SimplyScreensUiPreferences.getThemeMode(file));
        assertTrue(Files.readString(file).contains("other.setting=keep"));
    }
}
