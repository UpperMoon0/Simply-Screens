package com.nstut.simply_screens.screens;

import com.mojang.blaze3d.systems.RenderSystem;
import com.nstut.simply_screens.blocks.entities.ScreenBlockEntity;
import com.nstut.simply_screens.network.PacketRegistries;
import com.nstut.simply_screens.network.UpdateScreenC2SPacket;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Logger;

public class ImageUploadScreen extends Screen {

    private static final Logger LOGGER = Logger.getLogger(ImageUploadScreen.class.getName());

    private EditBox imagePathField;
    private final BlockPos blockEntityPos;
    private String initialImagePath; // Store the initial image path from the block entity

    public ImageUploadScreen(BlockPos blockEntityPos) {
        super(Component.literal("Image Upload Screen"));
        this.blockEntityPos = blockEntityPos; // Store the position of the block entity
    }

    @Override
    protected void init() {
        super.init();

        // Fetch the initial image path from the block entity
        fetchImagePathFromBlockEntity();

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // Initialize the EditBox for image path input
        this.imagePathField = new EditBox(this.font, centerX - 100, centerY - 30, 200, 20, Component.literal("Enter Image Path"));
        this.imagePathField.setMaxLength(255);

        // Set the prefilled value if available
        if (initialImagePath != null && !initialImagePath.isEmpty()) {
            this.imagePathField.setValue(initialImagePath);
        }

        // Create the "Upload Image" button
        Button uploadButton = Button.builder(Component.literal("Upload Image"), button -> {
                    String filePath = imagePathField.getValue(); // Get the input value
                    if (!filePath.isEmpty()) {
                        sendImagePathToServer(filePath); // Send the file path to the server
                    }
                })
                .pos(centerX - 60, centerY + 10)
                .size(120, 20)
                .build();

        // Add input field and button to the screen
        this.addRenderableWidget(this.imagePathField);
        this.addRenderableWidget(uploadButton);
    }

    @Override
    public void render(@NotNull GuiGraphics pGuiGraphics, int pMouseX, int pMouseY, float pPartialTick) {
        RenderSystem.clearColor(0f, 0f, 0f, 1f);
        super.render(pGuiGraphics, pMouseX, pMouseY, pPartialTick);

        // Render the input field
        imagePathField.render(pGuiGraphics, pMouseX, pMouseY, pPartialTick);
    }

    private void fetchImagePathFromBlockEntity() {
        // Retrieve the block entity and its image path on the client side
        if (this.minecraft != null && this.minecraft.level != null) {
            BlockEntity blockEntity = this.minecraft.level.getBlockEntity(this.blockEntityPos);
            if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                this.initialImagePath = screenBlockEntity.getImagePath(); // Get the current image path
            }
        }
    }

    private void sendImagePathToServer(String imagePath) {
        // Send the image path to the server with the block entity position
        PacketRegistries.sendToServer(new UpdateScreenC2SPacket(blockEntityPos, imagePath));
    }
}
