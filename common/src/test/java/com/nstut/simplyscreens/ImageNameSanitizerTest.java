package com.nstut.simplyscreens;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImageNameSanitizerTest {
    @Test
    void stripsControlsAndCapsNames() {
        String sanitized = ImageNameSanitizer.sanitize("  safe\nname\0" + "x".repeat(300));
        assertFalse(sanitized.chars().anyMatch(Character::isISOControl));
        assertTrue(sanitized.length() <= ImageNameSanitizer.MAX_LENGTH);
        assertTrue(sanitized.startsWith("safename"));
    }

    @Test
    void suppliesFallbackForMissingOrEmptyNames() {
        assertEquals("uploaded_image", ImageNameSanitizer.sanitize(null));
        assertEquals("uploaded_image", ImageNameSanitizer.sanitize("\n\0 "));
    }
}
