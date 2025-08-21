package com.nstut.simplyscreens.client.gui.widgets;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.nstut.simplyscreens.network.PacketRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import com.nstut.simplyscreens.helpers.ImageMetadata;

public class ImageListWidget extends AbstractWidget {
    private static final int ITEM_SIZE = 40;
    private static final int PADDING = 2;
    private static final int TEXT_HEIGHT = 12;
    private static final int ITEM_HEIGHT = ITEM_SIZE + TEXT_HEIGHT;
    private final Map<String, ResourceLocation> textureCache = new HashMap<>();
    private List<ImageEntry> imageFiles = new ArrayList<>();
    private List<ImageEntry> filteredImageFiles = new ArrayList<>();
    private double scrollAmount;
    private ImageEntry selected;
    private UUID displayedImage;
    private final Consumer<ImageEntry> onSelect;

    public static class ImageEntry {
        private final String displayName;
        private final UUID imageId;

        public ImageEntry(String displayName, String imageId) {
            this.displayName = displayName;
            this.imageId = UUID.fromString(imageId);
        }

        public String getDisplayName() {
            return displayName;
        }

        public UUID getImageId() {
            return imageId;
        }
    }

    public ImageListWidget(int x, int y, int width, int height, Component message, Consumer<ImageEntry> onSelect, UUID displayedImage) {
        super(x, y, width, height, message);
        this.onSelect = onSelect;
        this.displayedImage = displayedImage;
    }

    public void updateList(List<ImageMetadata> imageMetadata) {
        this.imageFiles = imageMetadata.stream()
                .map(meta -> new ImageEntry(meta.getName(), meta.getId()))
                .collect(Collectors.toList());
        this.filteredImageFiles = new ArrayList<>(this.imageFiles);
        this.scrollAmount = 0;
        if (displayedImage != null) {
            for (ImageEntry entry : imageFiles) {
                if (entry.getImageId().equals(displayedImage)) {
                    this.selected = entry;
                    onSelect.accept(entry);
                    break;
                }
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
        int contentHeight = ((filteredImageFiles.size() + itemsPerRow - 1) / itemsPerRow) * (ITEM_HEIGHT + PADDING);
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
            int itemY = this.getY() + PADDING + row * (ITEM_HEIGHT + PADDING) - (int) scrollAmount;

            if (itemY + ITEM_HEIGHT < this.getY() || itemY > this.getY() + this.height) {
                continue;
            }

            ImageEntry entry = filteredImageFiles.get(i);
            boolean isHovered = mouseX >= itemX && mouseX < itemX + ITEM_SIZE && mouseY >= itemY && mouseY < itemY + ITEM_HEIGHT;
            boolean isSelected = selected == entry;
            boolean isDisplayed = displayedImage != null && displayedImage.equals(entry.getImageId());

            int backgroundColor = isSelected ? 0xFF808080 : (isHovered ? 0xFF404040 : 0xFF202020);
            guiGraphics.fill(itemX, itemY, itemX + ITEM_SIZE, itemY + ITEM_HEIGHT, backgroundColor);

            if (isDisplayed) {
                guiGraphics.renderOutline(itemX, itemY, ITEM_SIZE, ITEM_HEIGHT, 0xFF00FF00);
            }

            ResourceLocation texture = textureCache.computeIfAbsent(entry.getImageId().toString(), id -> {
                PacketRegistries.CHANNEL.sendToServer(new com.nstut.simplyscreens.network.RequestImageDownloadC2SPacket(UUID.fromString(id)));
                return null;
            });

            if (texture != null) {
                RenderSystem.setShaderTexture(0, texture);
                guiGraphics.blit(texture, itemX + 2, itemY + 2, 0, 0, ITEM_SIZE - 4, ITEM_SIZE - 4, ITEM_SIZE - 4, ITEM_SIZE - 4);
            }

            Component displayName = Component.literal(entry.getDisplayName());
            int textWidth = Minecraft.getInstance().font.width(displayName);
            if (textWidth > ITEM_SIZE - 4) {
                displayName = Component.literal(Minecraft.getInstance().font.substrByWidth(displayName, ITEM_SIZE - 4).getString() + "...");
            }
            textWidth = Minecraft.getInstance().font.width(displayName);
            int textX = itemX + (ITEM_SIZE - textWidth) / 2;
            int textY = itemY + ITEM_SIZE + (TEXT_HEIGHT - 8) / 2;
            guiGraphics.drawString(Minecraft.getInstance().font, displayName, textX, textY, 0xFFFFFFFF);
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
            int itemY = this.getY() + PADDING + row * (ITEM_HEIGHT + PADDING) - (int) scrollAmount;

            if (mouseX >= itemX && mouseX < itemX + ITEM_SIZE && mouseY >= itemY && mouseY < itemY + ITEM_HEIGHT) {
                this.selected = filteredImageFiles.get(i);
                this.onSelect.accept(this.selected);
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int itemsPerRow = Math.max(1, (this.width - PADDING) / (ITEM_SIZE + PADDING));
        int contentHeight = ((filteredImageFiles.size() + itemsPerRow - 1) / itemsPerRow) * (ITEM_HEIGHT + PADDING);
        int maxScroll = Math.max(0, contentHeight - this.height);
        scrollAmount = Mth.clamp(scrollAmount - scrollY * 10, 0, maxScroll);
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        narrationElementOutput.add(NarratedElementType.TITLE, getMessage());

        if (this.selected != null) {
            narrationElementOutput.add(NarratedElementType.USAGE, Component.literal("Selected image: " + this.selected.getDisplayName()));
        } else {
            narrationElementOutput.add(NarratedElementType.USAGE, Component.literal("No image selected. Click to select an image."));
        }
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
        // This will be called when the list needs to be updated from the server.
        // We will request a new list from the server.
        PacketRegistries.CHANNEL.sendToServer(new com.nstut.simplyscreens.network.RequestImageListC2SPacket());
    }
    
    public void tick() {
        // No-op for now
    }

    public void setDisplayedImage(UUID displayedImage) {
        this.displayedImage = displayedImage;
    }

    public void receiveImageData(String imageId, byte[] imageData) {
        try {
            NativeImage nativeImage = NativeImage.read(new ByteArrayInputStream(imageData));
            DynamicTexture dynamicTexture = new DynamicTexture(nativeImage);
            ResourceLocation texture = Minecraft.getInstance().getTextureManager().register("simplyscreens/" + imageId, dynamicTexture);
            textureCache.put(imageId, texture);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}