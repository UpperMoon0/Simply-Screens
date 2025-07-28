package com.nstut.simplyscreens.client.screens;

import com.mojang.blaze3d.systems.RenderSystem;
import com.nstut.simplyscreens.DisplayMode;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.client.gui.widgets.ImageListWidget;
import com.nstut.simplyscreens.helpers.ClientImageCache;
import com.nstut.simplyscreens.network.PacketRegistries;
import com.nstut.simplyscreens.network.UpdateScreenC2SPacket;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ImageLoadScreen extends Screen {
    private static final ResourceLocation BACKGROUND_TEXTURE = new ResourceLocation(SimplyScreens.MOD_ID, "textures/gui/screen.png");

    private static final int SCREEN_WIDTH = 162;
    private static final int SCREEN_HEIGHT = 158;

    private DisplayMode currentMode = DisplayMode.INTERNET;

    private EditBox internetUrlField;
    private EditBox imageUrlField;
    private final BlockPos blockEntityPos;
    private String initialInternetUrl;
    private String initialImageUrl;
    private boolean initialMaintainAspectRatio = true;
    private DisplayMode initialDisplayMode = DisplayMode.INTERNET;
    private Checkbox maintainAspectCheckbox;
    private Button internetButton;
    private Button localButton;
    private ImageListWidget imageListWidget;
    private Button uploadButton;
    private Button selectButton;
    private Button uploadLocalFileButton;

    public ImageLoadScreen(BlockPos blockEntityPos) {
        super(Component.literal("Image Upload Screen"));
        this.blockEntityPos = blockEntityPos;
    }

    @Override
    protected void init() {
        super.init();

        fetchDataFromBlockEntity();

        int guiLeft = (this.width - SCREEN_WIDTH) / 2;
        int guiTop = (this.height - SCREEN_HEIGHT) / 2;

        // Internet and Local buttons
        internetButton = Button.builder(Component.literal("Internet"), button -> updateMode(DisplayMode.INTERNET))
                .pos(guiLeft + 8, guiTop + 32)
                .size(70, 20)
                .build();

        localButton = Button.builder(Component.literal("Local"), button -> updateMode(DisplayMode.LOCAL))
                .pos(guiLeft + 83, guiTop + 32)
                .size(70, 20)
                .build();

        // Initialize the EditBox for image path input
        this.internetUrlField = new EditBox(this.font, guiLeft + 10, guiTop + 64, 140, 20, Component.literal(""));
        this.internetUrlField.setMaxLength(255);
        this.internetUrlField.setValue(initialInternetUrl);

        this.imageUrlField = new EditBox(this.font, guiLeft + 10, guiTop + 64, 140, 20, Component.literal(""));
        this.imageUrlField.setMaxLength(255);
        this.imageUrlField.setValue(initialImageUrl);
        this.imageUrlField.setEditable(false);

        maintainAspectCheckbox = new Checkbox(guiLeft + 10, guiTop + 116, 20, 20, Component.literal("Maintain Aspect Ratio"), initialMaintainAspectRatio);

        // Create the "Load Image" button
        uploadButton = Button.builder(Component.literal("Load Image"), button -> {
                    String url = internetUrlField.getValue();
                    if (!url.isBlank() && isHttpUrl(url)) {
                        sendScreenInputsToServer();
                    }
                })
                .pos(guiLeft + 21, guiTop + 88)
                .size(120, 20)
                .build();

        selectButton = Button.builder(Component.literal("Select Image"), button -> {
                    ImageListWidget.ImageEntry selectedEntry = imageListWidget.getSelected();
                    if (selectedEntry != null) {
                        imageUrlField.setValue(selectedEntry.getImageHash());
                        sendScreenInputsToServer();
                        imageListWidget.setDisplayedImage(selectedEntry.getImageHash());
                    }
                })
                .pos(guiLeft + 21, guiTop + 108)
                .size(120, 20)
                .build();

        uploadLocalFileButton = Button.builder(Component.literal("Upload"), button -> openFileDialog())
                .pos(guiLeft + 21, guiTop + 128)
                .size(120, 20)
                .build();

        imageListWidget = new ImageListWidget(guiLeft + 10, guiTop + 54, 140, 50, Component.literal(""), entry -> {
            if (entry != null) {
                imageUrlField.setValue(entry.getDisplayName());
            }
        });

        if (initialImageUrl != null && !initialImageUrl.isBlank()) {
            imageListWidget.setDisplayedImage(initialImageUrl);
        }

        this.addRenderableWidget(internetButton);
        this.addRenderableWidget(localButton);
        this.addRenderableWidget(this.internetUrlField);
        this.addRenderableWidget(this.imageUrlField);
        this.addRenderableWidget(maintainAspectCheckbox);
        this.addRenderableWidget(uploadButton);
        this.addRenderableWidget(selectButton);
        this.addRenderableWidget(uploadLocalFileButton);
        this.addRenderableWidget(imageListWidget);

        updateMode(initialDisplayMode);
    }

    @Override
    public void render(@NotNull GuiGraphics pGuiGraphics, int pMouseX, int pMouseY, float pPartialTick) {
        // Draw the background
        RenderSystem.setShaderTexture(0, BACKGROUND_TEXTURE);
        int guiLeft = (this.width - SCREEN_WIDTH) / 2;
        int guiTop = (this.height - SCREEN_HEIGHT) / 2;
        pGuiGraphics.blit(BACKGROUND_TEXTURE, guiLeft, guiTop, 0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);

        // Draw the title at the top center
        String title = "Screen";
        int titleX = guiLeft + (SCREEN_WIDTH - this.font.width(title)) / 2;
        pGuiGraphics.drawString(this.font, title, titleX, guiTop + 10, 0x3F3F3F, false);

        // Draw the "Image source" label
        String sourceLabel = "Image source";
        int sourceLabelX = guiLeft + 12;
        pGuiGraphics.drawString(this.font, sourceLabel, sourceLabelX, guiTop + 22, 0x3F3F3F, false);

        if (currentMode == DisplayMode.INTERNET) {
            String pathLabel = "Image URL";
            int pathLabelX = guiLeft + 12;
            pGuiGraphics.drawString(this.font, pathLabel, pathLabelX, guiTop + 54, 0x3F3F3F, false);
        } else if (currentMode == DisplayMode.LOCAL) {
            String pathLabel = "Cached Images";
            int pathLabelX = guiLeft + 12;
            pGuiGraphics.drawString(this.font, pathLabel, pathLabelX, guiTop + 44, 0x3F3F3F, false);
        }

        // Draw widgets and other components
        super.render(pGuiGraphics, pMouseX, pMouseY, pPartialTick);
    }

    private void updateMode(DisplayMode newMode) {
        currentMode = newMode;

        internetButton.active = currentMode != DisplayMode.INTERNET;
        localButton.active = currentMode != DisplayMode.LOCAL;

        internetUrlField.setVisible(currentMode == DisplayMode.INTERNET);
        imageUrlField.setVisible(false);
        maintainAspectCheckbox.visible = true;

        uploadButton.visible = currentMode == DisplayMode.INTERNET;
        selectButton.visible = currentMode == DisplayMode.LOCAL;
        uploadLocalFileButton.visible = currentMode == DisplayMode.LOCAL;

        imageListWidget.setVisible(currentMode == DisplayMode.LOCAL);
    }

    private void openFileDialog() {
        String selectedFile = TinyFileDialogs.tinyfd_openFileDialog(
                "Select Image",
                null,
                null,
                "Image Files",
                false
        );
    
        if (selectedFile != null) {
            Path imagePath = Paths.get(selectedFile);
            ClientImageCache.sendImageToServer(imagePath, blockEntityPos, maintainAspectCheckbox.selected(), imageListWidget::refresh);
        }
    }
    
    @Override
    public void tick() {
        super.tick();
        imageListWidget.tick();
    }

    private void fetchDataFromBlockEntity() {
        if (this.minecraft != null && this.minecraft.level != null) {
            BlockEntity blockEntity = this.minecraft.level.getBlockEntity(blockEntityPos);
            if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                initialDisplayMode = screenBlockEntity.getDisplayMode();
                initialInternetUrl = screenBlockEntity.getInternetUrl();
                initialImageUrl = screenBlockEntity.getImageUrl();
                initialMaintainAspectRatio = screenBlockEntity.isMaintainAspectRatio();
            }
        }
    }

    private void sendScreenInputsToServer() {
        String internetUrl = internetUrlField.getValue();
        String imageUrl = imageUrlField.getValue();
        boolean maintainAspectRatio = maintainAspectCheckbox.selected();

        PacketRegistries.CHANNEL.sendToServer(new UpdateScreenC2SPacket(blockEntityPos, currentMode, imageUrl, internetUrl, maintainAspectRatio));
    }

    private boolean isHttpUrl(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.minecraft == null) {
            return false;
        }

        // Check if the inventory key is pressed and the EditBox is NOT focused
        if (this.minecraft.options.keyInventory.matches(keyCode, scanCode) && !this.internetUrlField.isFocused() && !this.imageUrlField.isFocused()) {
            this.onClose(); // Close the screen
            return true;    // Mark the key event as handled
        }

        // Pass other keys to the parent class for normal processing
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.internetUrlField.isMouseOver(mouseX, mouseY)) {
            this.internetUrlField.setFocused(true);
            this.imageUrlField.setFocused(false);
            return true;
        } else if (this.imageUrlField.isMouseOver(mouseX, mouseY)) {
            this.internetUrlField.setFocused(false);
            this.imageUrlField.setFocused(true);
            return true;
        }

        this.internetUrlField.setFocused(false);
        this.imageUrlField.setFocused(false);

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        super.onClose();
        imageListWidget.close();
    }

    public ImageListWidget getImageListWidget() {
        return imageListWidget;
    }
}
