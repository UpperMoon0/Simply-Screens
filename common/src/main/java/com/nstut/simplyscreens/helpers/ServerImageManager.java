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

    public static UUID saveImage(MinecraftServer server, String imageName, byte[] data) {
        try {
            String extension = "png";
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
            ImageMetadata metadata = new ImageMetadata(imageName, imageId.toString(), extension, imageName);
            try (FileWriter writer = new FileWriter(metadataFile)) {
                GSON.toJson(metadata, writer);
            }

            return imageId;
        } catch (IOException e) {
            SimplyScreens.LOGGER.error("Failed to save image", e);
            return null;
        }
    }

    private static Path getImagesDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("simply_screens_images");
    }

    public static byte[] getImageData(MinecraftServer server, UUID imageId) {
        Path imagesDir = getImagesDir(server);
        File[] imageFiles = imagesDir.toFile().listFiles((dir, name) -> name.startsWith(imageId.toString()) && !name.endsWith(".json"));

        if (imageFiles != null && imageFiles.length > 0) {
            try {
                return java.nio.file.Files.readAllBytes(imageFiles[0].toPath());
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