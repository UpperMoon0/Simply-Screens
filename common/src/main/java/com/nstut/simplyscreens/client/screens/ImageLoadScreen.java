package com.nstut.simplyscreens.client.screens;

import com.mojang.blaze3d.systems.RenderSystem;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.client.gui.widgets.ImageListWidget;
import com.nstut.simplyscreens.network.PacketRegistries;
import com.nstut.simplyscreens.network.RequestImageDownloadC2SPacket;
import com.nstut.simplyscreens.network.UpdateScreenC2SPacket;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Path;
import java.nio.file.Paths;

public class ImageLoadScreen extends Screen {
    private static final ResourceLocation BACKGROUND_TEXTURE = new ResourceLocation(SimplyScreens.MOD_ID, "textures/gui/screen.png");

    private static final int SCREEN_WIDTH = 176;
    private static final int SCREEN_HEIGHT = 166;

    private final BlockPos blockEntityPos;
    private java.util.UUID initialLocalHash;
    private boolean initialMaintainAspectRatio = true;

    private ImageListWidget imageListWidget;
    private Button selectButton;
    private Button uploadFromComputerButton;
    private Button downloadFromInternetButton;
    private Checkbox maintainAspectCheckbox;

    private EditBox urlInput;
    private Button downloadButton;
    private Button backButton;

    private enum Page {
        MAIN,
        DOWNLOAD
    }

    private Page currentPage = Page.MAIN;

    public ImageLoadScreen(BlockPos blockEntityPos) {
        super(Component.literal("Load Image"));
        this.blockEntityPos = blockEntityPos;
    }

    @Override
    protected void init() {
        super.init();
        fetchDataFromBlockEntity();
        setPage(Page.MAIN);
        imageListWidget.refresh();
    }

    private void setPage(Page page) {
        this.currentPage = page;
        clearWidgets();

        int guiLeft = (this.width - SCREEN_WIDTH) / 2;
        int guiTop = (this.height - SCREEN_HEIGHT) / 2;

        if (page == Page.MAIN) {
            initMainPage(guiLeft, guiTop);
        } else {
            initDownloadPage(guiLeft, guiTop);
        }
    }

    private void initMainPage(int guiLeft, int guiTop) {
        imageListWidget = new ImageListWidget(guiLeft + 8, guiTop + 8, 160, 84, Component.literal(""), this::onImageSelected, initialLocalHash);
        addRenderableWidget(imageListWidget);

        maintainAspectCheckbox = new Checkbox(guiLeft + 8, guiTop + 94, 160, 20, Component.literal("Maintain Aspect Ratio"), this.initialMaintainAspectRatio);
        addRenderableWidget(maintainAspectCheckbox);

        selectButton = Button.builder(Component.literal("Select"), button -> onSelect())
                .pos(guiLeft + 8, guiTop + 116)
                .size(160, 20)
                .build();
        selectButton.active = false;
        addRenderableWidget(selectButton);

        uploadFromComputerButton = Button.builder(Component.literal("Upload"), button -> onUploadFromComputer())
                .pos(guiLeft + 8, guiTop + 140)
                .size(78, 20)
                .build();
        addRenderableWidget(uploadFromComputerButton);

        downloadFromInternetButton = Button.builder(Component.literal("Download"), button -> setPage(Page.DOWNLOAD))
                .pos(guiLeft + 90, guiTop + 140)
                .size(78, 20)
                .build();
        addRenderableWidget(downloadFromInternetButton);
    }

    private void initDownloadPage(int guiLeft, int guiTop) {
        urlInput = new EditBox(this.font, guiLeft + 8, guiTop + 28, 160, 20, Component.literal("Image URL"));
        addRenderableWidget(urlInput);

        downloadButton = Button.builder(Component.literal("Download"), button -> onDownload())
                .pos(guiLeft + 8, guiTop + 52)
                .size(160, 20)
                .build();
        addRenderableWidget(downloadButton);

        backButton = Button.builder(Component.literal("Back"), button -> setPage(Page.MAIN))
                .pos(guiLeft + 8, guiTop + 76)
                .size(160, 20)
                .build();
        addRenderableWidget(backButton);
    }

    private void onImageSelected(ImageListWidget.ImageEntry entry) {
        selectButton.active = entry != null;
    }

    private void onSelect() {
        ImageListWidget.ImageEntry selectedEntry = imageListWidget.getSelected();
        if (selectedEntry != null) {
            sendScreenInputsToServer();
            imageListWidget.setDisplayedImage(selectedEntry.getImageId());
        }
    }

    private void onUploadFromComputer() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(stack.UTF8("*.png;*.jpg;*.jpeg;*.gif;*.webp"));
            filters.flip();

            String filePath = TinyFileDialogs.tinyfd_openFileDialog("Select Image", "", filters, "Image Files", false);

            if (filePath != null) {
                try {
                    Path path = Paths.get(filePath);
                    byte[] data = java.nio.file.Files.readAllBytes(path);
                    String fileName = path.getFileName().toString();
                    PacketRegistries.CHANNEL.sendToServer(new com.nstut.simplyscreens.network.UploadImageC2SPacket(blockEntityPos, fileName, data, maintainAspectCheckbox.selected()));
                    imageListWidget.refresh();
                } catch (java.io.IOException e) {
                    SimplyScreens.LOGGER.error("Failed to read image file", e);
                }
            }
        }
    }

    private void onDownload() {
        String url = urlInput.getValue();
        if (!url.isBlank()) {
            PacketRegistries.CHANNEL.sendToServer(new com.nstut.simplyscreens.network.DownloadImageFromUrlC2SPacket(blockEntityPos, url, maintainAspectCheckbox.selected()));
            setPage(Page.MAIN);
            imageListWidget.refresh();
        }
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        guiGraphics.blit(BACKGROUND_TEXTURE, (this.width - SCREEN_WIDTH) / 2, (this.height - SCREEN_HEIGHT) / 2, 0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        if (currentPage == Page.DOWNLOAD) {
            guiGraphics.drawString(this.font, "Image URL:", (this.width - SCREEN_WIDTH) / 2 + 8, (this.height - SCREEN_HEIGHT) / 2 + 12, 4210752);
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
                ScreenBlockEntity anchor = screenBlockEntity.getAnchorEntity();
                if (anchor != null) {
                    initialLocalHash = anchor.getImageId();
                    initialMaintainAspectRatio = anchor.isMaintainAspectRatio();
                }
            }
        }
    }

    private void sendScreenInputsToServer() {
        ImageListWidget.ImageEntry selectedEntry = imageListWidget.getSelected();
        if (selectedEntry != null) {
            if (this.minecraft != null && this.minecraft.level != null) {
                BlockEntity blockEntity = this.minecraft.level.getBlockEntity(blockEntityPos);
                if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                    ScreenBlockEntity anchor = screenBlockEntity.getAnchorEntity();
                    if (anchor != null) {
                        PacketRegistries.CHANNEL.sendToServer(new UpdateScreenC2SPacket(anchor.getBlockPos(), selectedEntry.getImageId()));
                    }
                }
            }
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.minecraft == null) {
            return false;
        }

        if (this.minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            if (currentPage == Page.MAIN) {
                this.onClose();
                return true;
            }
            if (currentPage == Page.DOWNLOAD && !urlInput.isFocused()) {
                this.onClose();
                return true;
            }
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
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