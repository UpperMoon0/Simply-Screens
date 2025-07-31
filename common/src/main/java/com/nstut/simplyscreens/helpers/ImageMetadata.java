package com.nstut.simplyscreens.helpers;

public class ImageMetadata {
    private final String name;
    private final String id;
    private final String extension;

    public ImageMetadata(String name, String id, String extension) {
        this.name = name;
        this.id = id;
        this.extension = extension;
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
}