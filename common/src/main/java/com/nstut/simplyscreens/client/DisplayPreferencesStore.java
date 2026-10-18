package com.nstut.simplyscreens.client;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Personal display distance, separate from server upload/storage/structure policies. */
public final class DisplayPreferencesStore {
    public static final int DEFAULT_DISTANCE = 64;
    private static final String FILE = "simplyscreens-ui.properties", KEY = "display.viewDistance";
    private DisplayPreferencesStore() { }
    public static int clamp(int value) { return Math.max(8, Math.min(512, value)); }
    public static synchronized int load(Path directory) {
        Properties p = read(directory.resolve(FILE));
        if (!p.containsKey(KEY)) {
            Properties legacy = read(directory.resolve("simply_screens.properties"));
            int distance = number(legacy.getProperty("viewDistance"));
            p.setProperty(KEY, Integer.toString(distance));
            write(directory.resolve(FILE), p);
        }
        return number(p.getProperty(KEY));
    }
    public static synchronized boolean save(Path directory, int distance) {
        Properties p = read(directory.resolve(FILE));
        p.setProperty(KEY, Integer.toString(clamp(distance)));
        return write(directory.resolve(FILE), p);
    }
    private static int number(String value) {
        try { return clamp(Integer.parseInt(value)); } catch (RuntimeException ignored) { return DEFAULT_DISTANCE; }
    }
    private static Properties read(Path file) {
        Properties p = new Properties();
        if (Files.isRegularFile(file)) try (var in = Files.newInputStream(file)) { p.load(in); }
        catch (IOException | IllegalArgumentException ignored) { }
        return p;
    }
    private static boolean write(Path file, Properties p) {
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "simplyscreens-ui-", ".tmp");
            try (var out = Files.newOutputStream(temporary)) { p.store(out, "Simply Screens personal preferences"); }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            return true;
        } catch (IOException error) {
            org.slf4j.LoggerFactory.getLogger("Simply Screens").warn("Could not save display preferences", error);
            return false;
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }
}
