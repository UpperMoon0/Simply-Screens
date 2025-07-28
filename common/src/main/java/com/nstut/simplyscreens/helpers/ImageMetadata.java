package com.nstut.simplyscreens.helpers;

public class ImageMetadata {
    private String name;
    private String extension;
    private long uploadedAt;

    public ImageMetadata(String name, String extension, long uploadedAt) {
        this.name = name;
        this.extension = extension;
        this.uploadedAt = uploadedAt;
    }

    public String getName() {
        return name;
    }

    public String getExtension() {
        return extension;
    }

    public long getUploadedAt() {
        return uploadedAt;
    }
}