package com.nstut.simplyscreens;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;

/** Version-neutral validation and decoding used by both client implementations. */
public final class ImageImportSupport {
    private ImageImportSupport() {}

    public static boolean isHttpUrl(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://"));
    }

    public static String fileNameFromUrl(String value) {
        if (!isHttpUrl(value)) return "downloaded_image.png";
        try {
            String path = new URI(value).getPath();
            if (path != null && path.contains(".")) {
                int slash = path.lastIndexOf('/');
                if (slash >= 0 && slash < path.length() - 1) return path.substring(slash + 1);
            }
        } catch (Exception ignored) {
        }
        return "downloaded_image.png";
    }

    public static boolean isSupportedFile(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }

    public static BufferedImage decode(byte[] data) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
        if (image == null) throw new IOException("Unsupported or corrupt image data");
        return image;
    }
}
