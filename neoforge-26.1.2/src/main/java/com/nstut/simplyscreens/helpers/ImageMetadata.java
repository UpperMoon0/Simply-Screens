package com.nstut.simplyscreens.helpers;

import net.minecraft.network.RegistryFriendlyByteBuf;

public class ImageMetadata {
    private final String name;
    private final String id;
    private final String extension;
    private final String ownerUUID;

    public ImageMetadata(String name, String id, String extension) {
        this(name, id, extension, null);
    }

    public ImageMetadata(String name, String id, String extension, String ownerUUID) {
        this.name = com.nstut.simplyscreens.ImageNameSanitizer.sanitize(name);
        this.id = id;
        this.extension = extension;
        this.ownerUUID = ownerUUID;
    }

    public ImageMetadata(RegistryFriendlyByteBuf buf) {
        this.name = buf.readUtf(com.nstut.simplyscreens.ImageNameSanitizer.MAX_LENGTH);
        this.id = buf.readUtf(36);
        this.extension = buf.readUtf(8);
        this.ownerUUID = buf.readBoolean() ? buf.readUtf(36) : null;
    }

    public void write(RegistryFriendlyByteBuf buf) {
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

