package com.nstut.simplyscreens.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class DisplayPreferencesStoreTest {
    @TempDir Path directory;
    @Test void defaultIsLocalAndDoesNotCreateServerConfig() {
        assertEquals(64,DisplayPreferencesStore.load(directory));
        assertFalse(Files.exists(directory.resolve("simply_screens.properties")));
    }
    @Test void importsLegacyDistanceOnceAndDoesNotAlterServerPolicies() throws Exception {
        Path legacy=directory.resolve("simply_screens.properties");
        String text="viewDistance=192\ndisableUpload=true\nmaxStorageTotal=123456789\n";
        Files.writeString(legacy,text);
        assertEquals(192,DisplayPreferencesStore.load(directory));
        assertEquals(text,Files.readString(legacy));
        Files.writeString(legacy,"viewDistance=32\n");
        assertEquals(192,DisplayPreferencesStore.load(directory));
    }
    @Test void changingDistancePersistsAndKeepsThemeAndUnknownKeys() throws Exception {
        Path file=directory.resolve("simplyscreens-ui.properties");
        Files.writeString(file,"ui.theme=light\nfuture.option=keep\ndisplay.viewDistance=128\n");
        assertTrue(DisplayPreferencesStore.save(directory,256));
        assertEquals(256,DisplayPreferencesStore.load(directory));
        String saved=Files.readString(file);
        assertTrue(saved.contains("ui.theme=light"));assertTrue(saved.contains("future.option=keep"));
        try(var files=Files.list(directory)) { assertEquals(1,files.count()); }
    }
    @Test void badLegacyOrPersonalValuesUseBoundedDefaults() throws Exception {
        Files.writeString(directory.resolve("simply_screens.properties"),"viewDistance=oops\n");
        assertEquals(64,DisplayPreferencesStore.load(directory));
        assertTrue(DisplayPreferencesStore.save(directory,99999));assertEquals(512,DisplayPreferencesStore.load(directory));
        assertTrue(DisplayPreferencesStore.save(directory,-1));assertEquals(8,DisplayPreferencesStore.load(directory));
    }
}
