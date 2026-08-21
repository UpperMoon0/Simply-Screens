package com.nstut.simplyscreens;

public final class ImageNameSanitizer {
    public static final int MAX_LENGTH = 128;

    private ImageNameSanitizer() { }

    public static String sanitize(String name) {
        if (name == null) return "uploaded_image";
        StringBuilder clean = new StringBuilder(Math.min(name.length(), MAX_LENGTH));
        name.codePoints().filter(codePoint -> !Character.isISOControl(codePoint)).forEach(codePoint -> {
            if (clean.length() + Character.charCount(codePoint) <= MAX_LENGTH) clean.appendCodePoint(codePoint);
        });
        String result = clean.toString().strip();
        return result.isEmpty() ? "uploaded_image" : result;
    }
}
