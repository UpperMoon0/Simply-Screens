package com.nstut.simplyscreens.client.gui.widgets;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class ImageListWidget extends AbstractWidget {
    private static final int ITEM_SIZE = 40;
    private static final int PADDING = 2;
    private final Map<String, ResourceLocation> textureCache = new HashMap<>();
    private List<ImageEntry> imageFiles = new ArrayList<>();
    private List<ImageEntry> filteredImageFiles = new ArrayList<>();
    private double scrollAmount;
    private ImageEntry selected;
    private String displayedImage;
    private final Consumer<ImageEntry> onSelect;

    public static class ImageEntry {
        private final File metadataFile;
        private final String displayName;
        private final String imageHash;
        private final String extension;

        public ImageEntry(File metadataFile, String displayName, String imageHash, String extension) {
            this.metadataFile = metadataFile;
            this.displayName = displayName;
            this.imageHash = imageHash;
            this.extension = extension;
        }

        public File getMetadataFile() {
            return metadataFile;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getImageHash() {
            return imageHash;
        }

        public String getImageExtension() {
            return extension;
        }
    }

    public ImageListWidget(int x, int y, int width, int height, Component message, Consumer<ImageEntry> onSelect) {
        super(x, y, width, height, message);
        this.onSelect = onSelect;
        loadImages();
    }

    private void loadImages() {
        if (Minecraft.getInstance().getSingleplayerServer() == null) return;
        Path imagesDir = Minecraft.getInstance().getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("simply_screens_images");
        if (Files.exists(imagesDir) && Files.isDirectory(imagesDir)) {
            try {
                this.imageFiles = Files.walk(imagesDir)
                        .filter(path -> path.toString().endsWith(".json"))
                        .map(path -> {
                            try {
                                String content = Files.readString(path);
                                com.nstut.simplyscreens.helpers.ImageMetadata metadata = new com.google.gson.Gson().fromJson(content, com.nstut.simplyscreens.helpers.ImageMetadata.class);
                                String imageHash = path.getFileName().toString().replace(".json", "");
                                return new ImageEntry(path.toFile(), metadata.getName(), imageHash, metadata.getExtension());
                            } catch (IOException e) {
                                e.printStackTrace();
                                return null;
                            }
                        })
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toList());
                this.filteredImageFiles = new ArrayList<>(this.imageFiles);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        guiGraphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, 0xFF000000);
    
        if (filteredImageFiles.isEmpty()) {
            Component message = Component.literal("No images found");
            int textWidth = Minecraft.getInstance().font.width(message);
            int textX = this.getX() + (this.width - textWidth) / 2;
            int textY = this.getY() + (this.height - 8) / 2;
            guiGraphics.drawString(Minecraft.getInstance().font, message, textX, textY, 0xFFFFFFFF);
            return;
        }
    
        int itemsPerRow = Math.max(1, (this.width - PADDING) / (ITEM_SIZE + PADDING));
        int contentHeight = ((filteredImageFiles.size() + itemsPerRow - 1) / itemsPerRow) * (ITEM_SIZE + PADDING);
        int maxScroll = Math.max(0, contentHeight - this.height);
    
        if (maxScroll > 0) {
            int scrollbarX = this.getX() + this.width - 6;
            int scrollbarHeight = (int) ((float) this.height / contentHeight * this.height);
            scrollbarHeight = Mth.clamp(scrollbarHeight, 32, this.height - 8);
            int scrollbarY = (int) (this.scrollAmount * (this.height - scrollbarHeight) / maxScroll) + this.getY();
            guiGraphics.fill(scrollbarX, scrollbarY, scrollbarX + 6, scrollbarY + scrollbarHeight, 0xFF888888);
        }
    
        guiGraphics.enableScissor(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height);
    
        for (int i = 0; i < filteredImageFiles.size(); i++) {
            int row = i / itemsPerRow;
            int col = i % itemsPerRow;

            int itemX = this.getX() + PADDING + col * (ITEM_SIZE + PADDING);
            int itemY = this.getY() + PADDING + row * (ITEM_SIZE + PADDING) - (int) scrollAmount;

            if (itemY + ITEM_SIZE < this.getY() || itemY > this.getY() + this.height) {
                continue;
            }

            ImageEntry entry = filteredImageFiles.get(i);
            boolean isHovered = mouseX >= itemX && mouseX < itemX + ITEM_SIZE && mouseY >= itemY && mouseY < itemY + ITEM_SIZE;
            boolean isSelected = selected == entry;
            boolean isDisplayed = displayedImage != null && displayedImage.equals(entry.getImageHash());

            int backgroundColor = isSelected ? 0xFF808080 : (isHovered ? 0xFF404040 : 0xFF202020);
            guiGraphics.fill(itemX, itemY, itemX + ITEM_SIZE, itemY + ITEM_SIZE, backgroundColor);

            if (isDisplayed) {
                guiGraphics.renderOutline(itemX, itemY, ITEM_SIZE, ITEM_SIZE, 0xFF00FF00);
            }

            ResourceLocation texture = textureCache.computeIfAbsent(entry.getImageHash(), hash -> {
                try {
                    File imageFile = new File(entry.getMetadataFile().getParentFile(), entry.getImageHash() + "." + entry.getImageExtension());
                    try (FileInputStream stream = new FileInputStream(imageFile)) {
                        NativeImage nativeImage = NativeImage.read(stream);
                        DynamicTexture dynamicTexture = new DynamicTexture(nativeImage);
                        return Minecraft.getInstance().getTextureManager().register(imageFile.getName(), dynamicTexture);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    return null;
                }
            });

            if (texture != null) {
                RenderSystem.setShaderTexture(0, texture);
                guiGraphics.blit(texture, itemX + 2, itemY + 2, 0, 0, ITEM_SIZE - 4, ITEM_SIZE - 4, ITEM_SIZE - 4, ITEM_SIZE - 4);
            }
        }
    
        guiGraphics.disableScissor();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.isMouseOver(mouseX, mouseY)) {
            return false;
        }

        int itemsPerRow = Math.max(1, (this.width - PADDING) / (ITEM_SIZE + PADDING));
        for (int i = 0; i < filteredImageFiles.size(); i++) {
            int row = i / itemsPerRow;
            int col = i % itemsPerRow;

            int itemX = this.getX() + PADDING + col * (ITEM_SIZE + PADDING);
            int itemY = this.getY() + PADDING + row * (ITEM_SIZE + PADDING) - (int) scrollAmount;

            if (mouseX >= itemX && mouseX < itemX + ITEM_SIZE && mouseY >= itemY && mouseY < itemY + ITEM_SIZE) {
                this.selected = filteredImageFiles.get(i);
                this.onSelect.accept(this.selected);
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int itemsPerRow = Math.max(1, (this.width - PADDING) / (ITEM_SIZE + PADDING));
        int contentHeight = ((filteredImageFiles.size() + itemsPerRow - 1) / itemsPerRow) * (ITEM_SIZE + PADDING);
        int maxScroll = Math.max(0, contentHeight - this.height);
        scrollAmount = Mth.clamp(scrollAmount - delta * (ITEM_SIZE + PADDING), 0, maxScroll);
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        // TODO: Implement narration
    }

    public void filter(String searchTerm) {
        if (searchTerm == null || searchTerm.isEmpty()) {
            this.filteredImageFiles = new ArrayList<>(this.imageFiles);
        } else {
            String lowerCaseSearchTerm = searchTerm.toLowerCase();
            this.filteredImageFiles = this.imageFiles.stream()
                    .filter(entry -> entry.getDisplayName().toLowerCase().contains(lowerCaseSearchTerm))
                    .collect(Collectors.toList());
        }
        this.scrollAmount = 0;
        if (!this.filteredImageFiles.contains(this.selected)) {
            this.selected = null;
        }
    }

    public ImageEntry getSelected() {
        return selected;
    }

    public void close() {
        textureCache.values().forEach(Minecraft.getInstance().getTextureManager()::release);
        textureCache.clear();
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public void refresh() {
        loadImages();
    }
    
    public void tick() {
        // No-op for now
    }

    public void setDisplayedImage(String displayedImage) {
        this.displayedImage = displayedImage;
    }
}