package com.nstut.simplyscreens.client.ui;

import com.nstut.simplyscreens.SimplyScreens;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class SimplyScreensUiPreferences {
    public static final String THEME_KEY = "ui.theme";
    private static final String FILE_NAME = "simplyscreens-ui.properties";

    private SimplyScreensUiPreferences() {
    }

    private static Path configFile() {
        String override = System.getProperty("simplyscreens.ui.config.dir");
        Path directory = override != null
                ? Path.of(override)
                : Minecraft.getInstance().gameDirectory.toPath().resolve("config");
        return directory.resolve(FILE_NAME);
    }

    public static synchronized UiThemeMode getThemeMode() {
        return getThemeMode(configFile());
    }

    public static synchronized void setThemeMode(UiThemeMode mode) {
        setThemeMode(configFile(), mode);
    }

    static UiThemeMode getThemeMode(Path file) {
        return UiThemeMode.fromConfigValue(load(file).getProperty(THEME_KEY, "dark"));
    }

    static void setThemeMode(Path file, UiThemeMode mode) {
        Properties properties = load(file);
        properties.setProperty(THEME_KEY, mode.configValue());
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            try (OutputStream output = Files.newOutputStream(file)) {
                properties.store(output, "Simply Screens UI preferences");
            }
        } catch (IOException e) {
            SimplyScreens.LOGGER.warn("Failed to persist Simply Screens UI preferences to {}", file, e);
        }
    }

    private static Properties load(Path file) {
        Properties properties = new Properties();
        if (!Files.exists(file)) return properties;
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        } catch (IOException e) {
            SimplyScreens.LOGGER.warn("Failed to read Simply Screens UI preferences from {}; using defaults", file, e);
        }
        return properties;
    }
}
