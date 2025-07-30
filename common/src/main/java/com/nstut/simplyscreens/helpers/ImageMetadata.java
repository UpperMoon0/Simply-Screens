package com.nstut.simplyscreens.helpers;

import com.nstut.simplyscreens.DisplayMode;

public class ImageMetadata {
    private final String id;
    private final String name;
    private final String extension;
    private final long uploadedAt;
    private final DisplayMode displayMode;
    private final String url;

    public ImageMetadata(String id, String name, String extension, long uploadedAt, DisplayMode displayMode, String url) {
        this.id = id;
        this.name = name;
        this.extension = extension;
        this.uploadedAt = uploadedAt;
        this.displayMode = displayMode;
        this.url = url;
    }

    public String getId() {
        return id;
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