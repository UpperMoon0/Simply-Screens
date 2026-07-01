package com.nstut.simplyscreens;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImageImportSupportTest {
    @Test
    void validatesUrlsAndExtractsFileNames() {
        assertTrue(ImageImportSupport.isHttpUrl("https://example.test/images/photo.jpg?size=large"));
        assertFalse(ImageImportSupport.isHttpUrl("file:///tmp/photo.jpg"));
        assertEquals("photo.jpg", ImageImportSupport.fileNameFromUrl("https://example.test/images/photo.jpg?size=large"));
        assertEquals("downloaded_image.png", ImageImportSupport.fileNameFromUrl("https://example.test/no-extension"));
    }

    @Test
    void recognizesAllSupportedExtensions() {
        assertTrue(ImageImportSupport.isSupportedFile("image.PNG"));
        assertTrue(ImageImportSupport.isSupportedFile("image.jpg"));
        assertTrue(ImageImportSupport.isSupportedFile("image.jpeg"));
        assertFalse(ImageImportSupport.isSupportedFile("image.gif"));
    }
}
