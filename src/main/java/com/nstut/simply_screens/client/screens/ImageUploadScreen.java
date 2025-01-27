package com.nstut.simply_screens.client.screens;

import com.mojang.blaze3d.systems.RenderSystem;
import com.nstut.simply_screens.SimplyScreens;
import com.nstut.simply_screens.blocks.entities.ScreenBlockEntity;
import com.nstut.simply_screens.network.PacketRegistries;
import com.nstut.simply_screens.network.UpdateScreenC2SPacket;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Logger;

public class ImageUploadScreen extends Screen {

    private static final Logger LOGGER = Logger.getLogger(ImageUploadScreen.class.getName());
    private static final ResourceLocation BACKGROUND_TEXTURE = new ResourceLocation(SimplyScreens.MOD_ID, "textures/gui/screen.png");

    private static final int SCREEN_WIDTH = 162;
    private static final int SCREEN_HEIGHT = 128;

    private EditBox imagePathField;
    private final BlockPos blockEntityPos;
    private String initialImagePath;

    public ImageUploadScreen(BlockPos blockEntityPos) {
        super(Component.literal("Image Upload Screen"));
        this.blockEntityPos = blockEntityPos;
    }

    @Override
    protected void init() {
        super.init();

        fetchImagePathFromBlockEntity();

        int guiLeft = (this.width - SCREEN_WIDTH) / 2;
        int guiTop = (this.height - SCREEN_HEIGHT) / 2;

        // Initialize the EditBox for image path input
        this.imagePathField = new EditBox(this.font, guiLeft + 10, guiTop + 40, 140, 20, Component.literal("Enter Image Path"));
        this.imagePathField.setMaxLength(255);

        if (initialImagePath != null && !initialImagePath.isEmpty()) {
            this.imagePathField.setValue(initialImagePath);
        }

        // Create the "Upload Image" button
        Button uploadButton = Button.builder(Component.literal("Upload Image"), button -> {
                    String filePath = imagePathField.getValue();
                    if (!filePath.isEmpty()) {
                        sendImagePathToServer(filePath);
                    }
                })
                .pos(guiLeft + 21, guiTop + 70)
                .size(120, 20)
                .build();

        this.addRenderableWidget(this.imagePathField);
        this.addRenderableWidget(uploadButton);
    }

    @Override
    public void render(@NotNull GuiGraphics pGuiGraphics, int pMouseX, int pMouseY, float pPartialTick) {
        // Draw the background
        RenderSystem.setShaderTexture(0, BACKGROUND_TEXTURE);
        int guiLeft = (this.width - SCREEN_WIDTH) / 2;
        int guiTop = (this.height - SCREEN_HEIGHT) / 2;
        pGuiGraphics.blit(BACKGROUND_TEXTURE, guiLeft, guiTop, 0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);

        // Draw widgets and other components
        super.render(pGuiGraphics, pMouseX, pMouseY, pPartialTick);
        this.imagePathField.render(pGuiGraphics, pMouseX, pMouseY, pPartialTick);
    }

    private void fetchImagePathFromBlockEntity() {
        if (this.minecraft != null && this.minecraft.level != null) {
            BlockEntity blockEntity = this.minecraft.level.getBlockEntity(this.blockEntityPos);
            if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                this.initialImagePath = screenBlockEntity.getImagePath();
            }
        }
    }

    private void sendImagePathToServer(String imagePath) {
        PacketRegistries.sendToServer(new UpdateScreenC2SPacket(blockEntityPos, imagePath));
    }
}
