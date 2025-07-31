package com.nstut.simplyscreens.helpers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.nstut.simplyscreens.SimplyScreens;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public class ServerImageManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static UUID saveImage(MinecraftServer server, String originalName, byte[] data, String contentType) {
        try {
            String extension = getImageExtension(data);

            if (extension == null) {
                SimplyScreens.LOGGER.error("Could not determine a valid image type for '{}' based on its content. It might be corrupted or an unsupported format.", originalName);
                return null;
            }

            SimplyScreens.LOGGER.info("Saving image. originalName: '{}', contentType: '{}', determined extension: '{}'", originalName, contentType, extension);

            UUID imageId = UUID.randomUUID();
            Path imagesDir = getImagesDir(server);

            if (!imagesDir.toFile().exists()) {
                imagesDir.toFile().mkdirs();
            }

            File imageFile = imagesDir.resolve(imageId + "." + extension).toFile();
            try (FileOutputStream fos = new FileOutputStream(imageFile)) {
                fos.write(data);
            }

            File metadataFile = imagesDir.resolve(imageId + ".json").toFile();
            String nameWithoutExtension = originalName.contains(".") ? originalName.substring(0, originalName.lastIndexOf('.')) : originalName;
            ImageMetadata metadata = new ImageMetadata(nameWithoutExtension, imageId.toString(), extension);
            try (FileWriter writer = new FileWriter(metadataFile)) {
                GSON.toJson(metadata, writer);
            }

            return imageId;
        } catch (IOException e) {
            SimplyScreens.LOGGER.error("Failed to save image", e);
            return null;
        }
    }
    private static String getImageExtension(byte[] data) {
        if (data == null || data.length < 4) {
            return null;
        }

        // PNG: 89 50 4E 47
        if (data[0] == (byte) 0x89 && data[1] == (byte) 0x50 && data[2] == (byte) 0x4E && data[3] == (byte) 0x47) {
            return "png";
        }

        // JPEG: FF D8 FF
        if (data[0] == (byte) 0xFF && data[1] == (byte) 0xD8 && data[2] == (byte) 0xFF) {
            return "jpg";
        }

        // GIF: 47 49 46 38
        if (data[0] == (byte) 0x47 && data[1] == (byte) 0x49 && data[2] == (byte) 0x46 && data[3] == (byte) 0x38) {
            return "gif";
        }

        return null;
    }


    private static Path getImagesDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("simply_screens_images");
    }

    public static ImageMetadata getImageMetadata(MinecraftServer server, UUID imageId) {
        Path imagesDir = getImagesDir(server);
        File metadataFile = imagesDir.resolve(imageId + ".json").toFile();

        if (metadataFile.exists()) {
            try (FileReader reader = new FileReader(metadataFile)) {
                return GSON.fromJson(reader, ImageMetadata.class);
            } catch (IOException e) {
                SimplyScreens.LOGGER.error("Failed to read image metadata for " + imageId, e);
            }
        }

        return null;
    }

    public static byte[] getImageData(MinecraftServer server, UUID imageId) {
        ImageMetadata metadata = getImageMetadata(server, imageId);
        if (metadata == null) {
            return null;
        }

        Path imagesDir = getImagesDir(server);
        File imageFile = imagesDir.resolve(imageId + "." + metadata.getExtension()).toFile();

        if (imageFile.exists()) {
            try {
                return Files.readAllBytes(imageFile.toPath());
            } catch (IOException e) {
                SimplyScreens.LOGGER.error("Failed to read image data for " + imageId, e);
            }
        }

        return null;
    }

    public static List<ImageMetadata> getImageList(MinecraftServer server) {
        List<ImageMetadata> imageList = new ArrayList<>();
        Path imagesDir = getImagesDir(server);

        if (Files.exists(imagesDir) && Files.isDirectory(imagesDir)) {
            try (Stream<Path> paths = Files.walk(imagesDir)) {
                paths.filter(path -> path.toString().endsWith(".json"))
                        .forEach(path -> {
                            try (FileReader reader = new FileReader(path.toFile())) {
                                imageList.add(GSON.fromJson(reader, ImageMetadata.class));
                            } catch (IOException e) {
                                SimplyScreens.LOGGER.error("Failed to read image metadata", e);
                            }
                        });
            } catch (IOException e) {
                SimplyScreens.LOGGER.error("Failed to list images", e);
            }
        }

        return imageList;
    }
}