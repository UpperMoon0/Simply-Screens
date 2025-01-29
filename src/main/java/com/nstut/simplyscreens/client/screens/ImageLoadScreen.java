package com.nstut.simplyscreens.client.screens;

import com.mojang.blaze3d.systems.RenderSystem;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
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

import java.util.logging.Logger;

public class ImageLoadScreen extends Screen {

    private static final Logger LOGGER = Logger.getLogger(ImageLoadScreen.class.getName());
    private static final ResourceLocation BACKGROUND_TEXTURE = new ResourceLocation(SimplyScreens.MOD_ID, "textures/gui/screen.png");

    private static final int SCREEN_WIDTH = 162;
    private static final int SCREEN_HEIGHT = 128;

    private EditBox imagePathField;
    private final BlockPos blockEntityPos;
    private String initialImagePath;
    private boolean initialMaintainAspectRatio = true;
    private Checkbox maintainAspectCheckbox;

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

        // Initialize the EditBox for image path input
        this.imagePathField = new EditBox(this.font, guiLeft + 10, guiTop + 40, 140, 20, Component.literal(""));
        this.imagePathField.setMaxLength(255);

        if (initialImagePath != null && !initialImagePath.isBlank()) {
            this.imagePathField.setValue(initialImagePath);
        }

        maintainAspectCheckbox = new Checkbox(guiLeft + 10, guiTop + 70, 20, 20, Component.literal("Maintain Aspect Ratio"), initialMaintainAspectRatio);

        this.addRenderableWidget(maintainAspectCheckbox);

        // Create the "Upload Image" button
        Button uploadButton = Button.builder(Component.literal("Load Image"), button -> {
                    String filePath = imagePathField.getValue();
                    if (!filePath.isBlank()) {
                        sendScreenInputsToServer(filePath, maintainAspectCheckbox.selected());
                    }
                })
                .pos(guiLeft + 21, guiTop + 98)
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

        // Draw the title at the top center
        String title = "Screen";
        int titleX = guiLeft + (SCREEN_WIDTH - this.font.width(title)) / 2;
        pGuiGraphics.drawString(this.font, title, titleX, guiTop + 10, 0x3F3F3F, false);

        String pathLabel = "Image Path / URL:";
        int pathLabelX = guiLeft + 12;
        pGuiGraphics.drawString(this.font, pathLabel, pathLabelX, guiTop + 27, 0x3F3F3F, false);

        // Draw widgets and other components
        super.render(pGuiGraphics, pMouseX, pMouseY, pPartialTick);
        this.imagePathField.render(pGuiGraphics, pMouseX, pMouseY, pPartialTick);
    }

    private void fetchDataFromBlockEntity() {
        if (this.minecraft != null && this.minecraft.level != null) {
            BlockEntity blockEntity = this.minecraft.level.getBlockEntity(blockEntityPos);
            if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                initialImagePath = screenBlockEntity.getImagePath();
                initialMaintainAspectRatio = screenBlockEntity.isMaintainAspectRatio();
            }
        }
    }

    private void sendScreenInputsToServer(String imagePath, boolean maintainAspectRatio) {
        PacketRegistries.sendToServer(new UpdateScreenC2SPacket(blockEntityPos, imagePath, maintainAspectRatio));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.minecraft == null) {
            return false;
        }

        // Check if the inventory key is pressed and the EditBox is NOT focused
        if (this.minecraft.options.keyInventory.matches(keyCode, scanCode) && !this.imagePathField.isFocused()) {
            this.onClose(); // Close the screen
            return true;    // Mark the key event as handled
        }

        // Pass other keys to the parent class for normal processing
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Check if the click is inside the EditBox bounds
        if (this.imagePathField.isFocused() && this.imagePathField.isMouseOver(mouseX, mouseY)) {
            // Lose focus if already focused and clicked
            this.imagePathField.setFocused(false);
            return true;
        }

        // Otherwise, handle the default behavior
        return super.mouseClicked(mouseX, mouseY, button);
    }

}

