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
        this.name = name;
        this.id = id;
        this.extension = extension;
        this.ownerUUID = ownerUUID;
    }

    public ImageMetadata(RegistryFriendlyByteBuf buf) {
        this.name = buf.readUtf();
        this.id = buf.readUtf();
        this.extension = buf.readUtf();
        this.ownerUUID = buf.readBoolean() ? buf.readUtf() : null;
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(name);
        buf.writeUtf(id);
        buf.writeUtf(extension);
        buf.writeBoolean(ownerUUID != null);
        if (ownerUUID != null) {
            buf.writeUtf(ownerUUID);
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
