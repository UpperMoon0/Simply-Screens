package com.nstut.simplyscreens.helpers;

import com.nstut.simplyscreens.DisplayMode;

public class ImageMetadata {
    private String name;
    private String extension;
    private long uploadedAt;
    private DisplayMode displayMode;
    private String url;

    public ImageMetadata(String name, String extension, long uploadedAt) {
        this(name, extension, uploadedAt, DisplayMode.LOCAL, null);
    }

    public ImageMetadata(String name, String extension, long uploadedAt, DisplayMode displayMode, String url) {
        this.name = name;
        this.extension = extension;
        this.uploadedAt = uploadedAt;
        this.displayMode = displayMode;
        this.url = url;
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

    public DisplayMode getDisplayMode() {
        return displayMode;
    }

    public String getUrl() {
        return url;
    }
}