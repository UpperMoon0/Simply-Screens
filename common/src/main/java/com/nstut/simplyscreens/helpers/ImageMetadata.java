package com.nstut.simplyscreens.helpers;

public class ImageMetadata {
    private final String name;
    private final String id;
    private final String extension;
    private final String source;

    public ImageMetadata(String name, String id, String extension, String source) {
        this.name = name;
        this.id = id;
        this.extension = extension;
        this.source = source;
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

    public String getSource() {
        return source;
    }
}