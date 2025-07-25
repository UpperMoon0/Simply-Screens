package com.nstut.simplyscreens.client.screens;

import com.mojang.blaze3d.systems.RenderSystem;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
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

import java.nio.file.Files;
import java.nio.file.Path;

public class ImageLoadScreen extends Screen {
    private static final ResourceLocation BACKGROUND_TEXTURE = new ResourceLocation(SimplyScreens.MOD_ID, "textures/gui/screen.png");

    private static final int SCREEN_WIDTH = 162;
    private static final int SCREEN_HEIGHT = 128;

    private enum Mode {
        INTERNET,
        LOCAL
    }

    private Mode currentMode = Mode.INTERNET;

    private EditBox imagePathField;
    private final BlockPos blockEntityPos;
    private String initialImagePath;
    private boolean initialMaintainAspectRatio = true;
    private Checkbox maintainAspectCheckbox;
    private Button internetButton;
    private Button localButton;

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
        internetButton = Button.builder(Component.literal("Internet"), button -> updateMode(Mode.INTERNET))
                .pos(guiLeft + 8, guiTop + 32)
                .size(70, 20)
                .build();

        localButton = Button.builder(Component.literal("Local"), button -> updateMode(Mode.LOCAL))
                .pos(guiLeft + 83, guiTop + 32)
                .size(70, 20)
                .build();

        // Initialize the EditBox for image path input
        this.imagePathField = new EditBox(this.font, guiLeft + 10, guiTop + 64, 140, 20, Component.literal(""));
        this.imagePathField.setMaxLength(255);

        if (initialImagePath != null && !initialImagePath.isBlank()) {
            this.imagePathField.setValue(initialImagePath);
        }

        maintainAspectCheckbox = new Checkbox(guiLeft + 10, guiTop + 86, 20, 20, Component.literal("Maintain Aspect Ratio"), initialMaintainAspectRatio);

        // Create the "Upload Image" button
        Button uploadButton = Button.builder(Component.literal("Load Image"), button -> {
                    String filePath = imagePathField.getValue();
                    if (!filePath.isBlank()) {
                        if (isHttpUrl(filePath)) {
                            sendScreenInputsToServer(filePath, maintainAspectCheckbox.selected());
                        } else {
                            ClientImageCache.sendImageToServer(Path.of(filePath), blockEntityPos);
                        }
                    }
                })
                .pos(guiLeft + 21, guiTop + 108)
                .size(120, 20)
                .build();

        this.addRenderableWidget(internetButton);
        this.addRenderableWidget(localButton);
        this.addRenderableWidget(this.imagePathField);
        this.addRenderableWidget(maintainAspectCheckbox);
        this.addRenderableWidget(uploadButton);

        updateMode(currentMode);
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

        if (currentMode == Mode.INTERNET) {
            String pathLabel = "Image URL";
            int pathLabelX = guiLeft + 12;
            pGuiGraphics.drawString(this.font, pathLabel, pathLabelX, guiTop + 54, 0x3F3F3F, false);
        }

        // Draw widgets and other components
        super.render(pGuiGraphics, pMouseX, pMouseY, pPartialTick);
    }

    private void updateMode(Mode newMode) {
        currentMode = newMode;

        internetButton.active = currentMode != Mode.INTERNET;
        localButton.active = currentMode != Mode.LOCAL;

        imagePathField.setVisible(currentMode == Mode.INTERNET);
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
        PacketRegistries.CHANNEL.sendToServer(new UpdateScreenC2SPacket(blockEntityPos, imagePath, maintainAspectRatio));
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

