package com.nstut.simplyscreens.helpers;

public class ImageMetadata {
    private String name;
    private String hash;
    private String extension;
    private long uploadedAt;

    public ImageMetadata(String name, String hash, String extension, long uploadedAt) {
        this.name = name;
        this.hash = hash;
        this.extension = extension;
        this.uploadedAt = uploadedAt;
    }

    public String getName() {
        return name;
    }

    public String getHash() {
        return hash;
    }

    public String getExtension() {
        return extension;
    }

    public long getUploadedAt() {
        return uploadedAt;
    }
}