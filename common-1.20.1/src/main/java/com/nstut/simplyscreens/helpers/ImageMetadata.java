package com.nstut.simplyscreens.helpers;

import net.minecraft.network.FriendlyByteBuf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class ImageMetadata {
    private final String name;
    private final String id;
    private final String extension;
    private final String ownerUUID;

    private static final Set<String> VALID_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp");

    public ImageMetadata(String name, String id, String extension) {
        this(name, id, extension, null);
    }

    public ImageMetadata(String name, String id, String extension, String ownerUUID) {
        this.name = com.nstut.simplyscreens.ImageNameSanitizer.sanitize(name);
        this.id = id;
        this.extension = extension;
        this.ownerUUID = ownerUUID;
    }

    /**
     * Validates and normalizes metadata loaded from disk. Gson can instantiate
     * ImageMetadata without invoking the sanitizing constructor, so pre-0.8.4
     * data with oversized names/ids or corrupt fields must be reconciled here.
     * Returns null when the entry is unusable or its backing image file is missing.
     */
    public static ImageMetadata validateAndNormalize(ImageMetadata raw, Path imagesDir) {
        return validateAndNormalize(raw, imagesDir, null);
    }

    public static ImageMetadata validateAndNormalize(ImageMetadata raw, Path imagesDir, String expectedImageId) {
        if (raw == null) return null;
        String name = com.nstut.simplyscreens.ImageNameSanitizer.sanitize(raw.name);
        if (!isValidUuid(raw.id)) return null;
        if (expectedImageId != null && !expectedImageId.equals(raw.id)) return null;
        String extension = normalizeExtension(raw.extension);
        if (extension == null) return null;
        if (raw.ownerUUID != null && !isValidUuid(raw.ownerUUID)) return null;
        String ownerUUID = raw.ownerUUID;
        if (imagesDir != null) {
            Path primary = imagesDir.resolve(raw.id + "." + extension);
            if (!Files.exists(primary)) {
                String alt = "jpeg".equals(extension) ? "jpg" : extension;
                if (alt.equals(extension) || !Files.exists(imagesDir.resolve(raw.id + "." + alt))) {
                    return null;
                }
                extension = alt;
            }
        }
        return new ImageMetadata(name, raw.id, extension, ownerUUID);
    }

    private static boolean isValidUuid(String value) {
        if (value == null || value.length() > 36) return false;
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String normalizeExtension(String value) {
        if (value == null) return null;
        String lower = value.toLowerCase(Locale.ROOT);
        return VALID_EXTENSIONS.contains(lower) ? lower : null;
    }

    public ImageMetadata(FriendlyByteBuf buf) {
        this.name = buf.readUtf(com.nstut.simplyscreens.ImageNameSanitizer.MAX_LENGTH);
        this.id = buf.readUtf(36);
        this.extension = buf.readUtf(8);
        this.ownerUUID = buf.readBoolean() ? buf.readUtf(36) : null;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(name, com.nstut.simplyscreens.ImageNameSanitizer.MAX_LENGTH);
        buf.writeUtf(id, 36);
        buf.writeUtf(extension, 8);
        buf.writeBoolean(ownerUUID != null);
        if (ownerUUID != null) {
            buf.writeUtf(ownerUUID, 36);
        }
    }

    public String getName() {
        return name;
    }

    public String getId() {
        return id;
    }

    public String getExtension() {
        return extension;
    }

    public String getOwnerUUID() {
        return ownerUUID;
    }
}
