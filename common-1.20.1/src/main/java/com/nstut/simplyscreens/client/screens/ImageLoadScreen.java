package com.nstut.simplyscreens.client.screens;

import com.nstut.simplyscreens.Config;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.client.gui.widgets.ImageListWidget;
import com.nstut.simplyscreens.network.DownloadImageFromUrlC2SPacket;
import com.nstut.simplyscreens.network.PacketRegistries;
import com.nstut.simplyscreens.network.UpdateScreenIdC2SPacket;
import com.nstut.simplyscreens.network.UploadImageChunkC2SPacket;
import com.nstut.simplyscreens.network.UpdateScreenAspectRatioC2SPacket;
import com.nstut.simplyscreens.network.UpdateScreenSelectedImageC2SPacket;
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
import java.util.UUID;

public class ImageLoadScreen extends Screen {
    private static final int CHUNK_SIZE = 1024 * 30; // 30KB
    private static final ResourceLocation BACKGROUND_TEXTURE = new ResourceLocation(SimplyScreens.MOD_ID, "textures/gui/screen.png");

    private static final int SCREEN_WIDTH = 162;
    private static final int SCREEN_HEIGHT_BASE = 240;
    private int screenHeight = SCREEN_HEIGHT_BASE;

    private final BlockPos blockEntityPos;
    private java.util.UUID initialLocalHash;
    private String initialScreenId;
    private boolean initialMaintainAspectRatio = true;

    private ImageListWidget imageListWidget;
    private Button selectButton;
    private Button uploadFromComputerButton;
    private Button linkButton;
    private Button goButton;
    private Checkbox maintainAspectCheckbox;
    private EditBox searchBar;
    private EditBox screenIdField;
    private EditBox urlField;


    public ImageLoadScreen(BlockPos blockEntityPos) {
        super(Component.translatable("gui.simplyscreens.screen.title"));
        this.blockEntityPos = blockEntityPos;
    }

    @Override
    protected void init() {
        super.init();
        fetchDataFromBlockEntity();

        int guiLeft = (this.width - SCREEN_WIDTH) / 2;
        int guiTop = (this.height - screenHeight) / 2;

        searchBar = new EditBox(this.font, guiLeft + 8, guiTop + 23, 145, 20, Component.translatable("gui.simplyscreens.screen.search.placeholder"));
        searchBar.setResponder(searchTerm -> {
            if (this.imageListWidget != null) {
                this.imageListWidget.filter(searchTerm);
            }
        });
        addRenderableWidget(searchBar);

        imageListWidget = new ImageListWidget(guiLeft + 8, guiTop + 47, 145, 60, Component.literal(""), this::onImageSelected, initialLocalHash);
        addRenderableWidget(imageListWidget);

        maintainAspectCheckbox = new Checkbox(guiLeft + 8, guiTop + 112, 145, 20, Component.translatable("gui.simplyscreens.screen.maintain_aspect"), this.initialMaintainAspectRatio) {
            @Override
            public void onPress() {
                super.onPress();
                if (minecraft != null && minecraft.level != null) {
                    BlockEntity blockEntity = minecraft.level.getBlockEntity(blockEntityPos);
                    if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                        ScreenBlockEntity anchor = screenBlockEntity.getAnchorEntity();
                        if (anchor != null) {
                            PacketRegistries.CHANNEL.sendToServer(new UpdateScreenAspectRatioC2SPacket(anchor.getBlockPos(), this.selected()));
                        }
                    }
                }
            }
        };
        addRenderableWidget(maintainAspectCheckbox);

        // Screen ID field
        screenIdField = new EditBox(this.font, guiLeft + 8, guiTop + 137, 145, 20, Component.translatable("gui.simplyscreens.screen.id.placeholder"));
        screenIdField.setValue(initialScreenId != null ? initialScreenId : "");
        addRenderableWidget(screenIdField);

        selectButton = Button.builder(Component.translatable("gui.simplyscreens.screen.select"), button -> onSelect())
                .pos(guiLeft + 8, guiTop + 162)
                .size(145, 20)
                .build();
        selectButton.active = false;
        addRenderableWidget(selectButton);

        uploadFromComputerButton = Button.builder(Component.translatable("gui.simplyscreens.screen.upload"), button -> onUploadFromComputer())
                .pos(guiLeft + 8, guiTop + 187)
                .size(145, 20)
                .build();
        uploadFromComputerButton.visible = !Config.DISABLE_UPLOAD;
        addRenderableWidget(uploadFromComputerButton);

        // Link button for URL download
        linkButton = Button.builder(Component.translatable("gui.simplyscreens.screen.link"), button -> onToggleLink())
                .pos(guiLeft + 8, guiTop + 212)
                .size(145, 20)
                .build();
        linkButton.visible = !Config.DISABLE_URL_DOWNLOAD;
        addRenderableWidget(linkButton);

        setInitialFocus(searchBar);
        imageListWidget.refresh();
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
            filters.put(stack.UTF8("*.png;*.jpg;*.jpeg"));
            filters.flip();

            String filePath = TinyFileDialogs.tinyfd_openFileDialog(Component.translatable("gui.simplyscreens.screen.dialog.select_image").getString(), "", filters, Component.translatable("gui.simplyscreens.screen.dialog.image_files").getString(), false);

            if (filePath != null) {
                try {
                    Path path = Paths.get(filePath);
                    byte[] data = java.nio.file.Files.readAllBytes(path);

                    if (data.length > Config.MAX_UPLOAD_SIZE) {
                        TinyFileDialogs.tinyfd_messageBox(Component.translatable("gui.simplyscreens.screen.dialog.upload_error").getString(), Component.translatable("gui.simplyscreens.screen.upload.error.size", Config.MAX_UPLOAD_SIZE / 1024 / 1024).getString(), "ok", "error", true);
                        return;
                    }

                    String fileName = path.getFileName().toString();

                    UUID transactionId = UUID.randomUUID();
                    int totalChunks = (int) Math.ceil((double) data.length / CHUNK_SIZE);

                    for (int i = 0; i < totalChunks; i++) {
                        int start = i * CHUNK_SIZE;
                        int end = Math.min(data.length, start + CHUNK_SIZE);
                        byte[] chunk = new byte[end - start];
                        System.arraycopy(data, start, chunk, 0, chunk.length);

                        PacketRegistries.CHANNEL.sendToServer(new UploadImageChunkC2SPacket(blockEntityPos, transactionId, i, totalChunks, chunk, i == 0 ? fileName : null));
                    }
                    imageListWidget.refresh();
                } catch (java.io.IOException e) {
                    SimplyScreens.LOGGER.error("Failed to read image file", e);
                }
            }
        }
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int guiLeft = (this.width - SCREEN_WIDTH) / 2;
        int guiTop = (this.height - screenHeight) / 2;
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawString(this.font, this.title, (this.width - this.font.width(this.title)) / 2, guiTop + 8, 0x404040, false);

        // Draw screen ID label
        guiGraphics.drawString(this.font, Component.translatable("gui.simplyscreens.screen.id.label"), guiLeft + 8, guiTop + 127, 0x404040, false);
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics) {
        super.renderBackground(guiGraphics);
        int guiLeft = (this.width - SCREEN_WIDTH) / 2;
        int guiTop = (this.height - screenHeight) / 2;
        guiGraphics.blit(BACKGROUND_TEXTURE, guiLeft, guiTop, 0, 0, SCREEN_WIDTH, screenHeight);
    }

    @Override
    public void tick() {
        super.tick();
        searchBar.tick();
        if (screenIdField != null) {
            screenIdField.tick();
        }
        if (urlField != null) {
            urlField.tick();
        }
        imageListWidget.tick();
    }

    private void fetchDataFromBlockEntity() {
        if (this.minecraft != null && this.minecraft.level != null) {
            BlockEntity blockEntity = this.minecraft.level.getBlockEntity(blockEntityPos);
            if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                ScreenBlockEntity anchor = screenBlockEntity.getAnchorEntity();
                if (anchor != null) {
                    initialLocalHash = anchor.getImageId();
                    initialScreenId = anchor.getScreenId();
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
                        PacketRegistries.CHANNEL.sendToServer(new UpdateScreenSelectedImageC2SPacket(anchor.getBlockPos(), selectedEntry.getImageId()));
                    }
                }
            }
        }

        // Send screen ID to server
        String screenId = screenIdField.getValue();
        if (!screenId.isEmpty()) {
            if (this.minecraft != null && this.minecraft.level != null) {
                BlockEntity blockEntity = this.minecraft.level.getBlockEntity(blockEntityPos);
                if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                    ScreenBlockEntity anchor = screenBlockEntity.getAnchorEntity();
                    if (anchor != null) {
                        PacketRegistries.CHANNEL.sendToServer(new UpdateScreenIdC2SPacket(anchor.getBlockPos(), screenId));
                    }
                }
            }
        }
    }

    private void onToggleLink() {
        // Toggle URL input field visibility
        if (urlField == null) {
            int guiLeft = (this.width - SCREEN_WIDTH) / 2;
            int guiTop = (this.height - screenHeight) / 2;

            urlField = new EditBox(this.font, guiLeft + 8, guiTop + 237, 115, 20, Component.translatable("gui.simplyscreens.screen.url.placeholder"));
            addRenderableWidget(urlField);

            goButton = Button.builder(Component.translatable("gui.simplyscreens.screen.go"), button -> onDownloadFromUrl())
                    .pos(guiLeft + 125, guiTop + 237)
                    .size(28, 20)
                    .build();
            addRenderableWidget(goButton);

            // Adjust screen height to accommodate URL field
            screenHeight = 260;
        } else {
            urlField.visible = !urlField.visible;
            goButton.visible = !goButton.visible;
        }
    }

    private void onDownloadFromUrl() {
        if (urlField == null || urlField.getValue().isEmpty()) {
            return;
        }

        String url = urlField.getValue();
        String fileName = null;

        // Extract filename from URL if possible
        try {
            java.net.URL parsedUrl = new java.net.URL(url);
            String path = parsedUrl.getPath();
            if (path != null && !path.isEmpty()) {
                int lastSlash = path.lastIndexOf('/');
                if (lastSlash >= 0 && lastSlash < path.length() - 1) {
                    fileName = path.substring(lastSlash + 1);
                }
            }
        } catch (java.net.MalformedURLException e) {
            SimplyScreens.LOGGER.error("Invalid URL: {}", url, e);
            return;
        }

        PacketRegistries.CHANNEL.sendToServer(new DownloadImageFromUrlC2SPacket(blockEntityPos, url, fileName));

        // Refresh image list after download
        imageListWidget.refresh();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.minecraft == null) {
            return false;
        }

        if (this.searchBar.isFocused() && this.searchBar.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }

        if (this.minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            this.onClose();
            return true;
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