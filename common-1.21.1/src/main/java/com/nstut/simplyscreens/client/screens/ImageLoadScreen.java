package com.nstut.simplyscreens.client.screens;

import com.nstut.simplyscreens.Config;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.client.gui.widgets.ImageListWidget;
import com.nstut.simplyscreens.network.PacketRegistries;
import com.nstut.simplyscreens.network.UpdateScreenAspectRatioC2SPacket;
import com.nstut.simplyscreens.network.UpdateScreenIdC2SPacket;
import com.nstut.simplyscreens.network.UpdateScreenSelectedImageC2SPacket;
import com.nstut.simplyscreens.network.UploadImageChunkC2SPacket;
import com.nstut.simplyscreens.network.DownloadImageFromUrlC2SPacket;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
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
    private static final ResourceLocation BACKGROUND_TEXTURE = ResourceLocation.fromNamespaceAndPath(SimplyScreens.MOD_ID, "textures/gui/screen.png");

    private static final int SCREEN_WIDTH = 176;
    private static final int SCREEN_HEIGHT = 220;

    private final BlockPos blockEntityPos;
    private UUID initialLocalHash;
    private boolean initialMaintainAspectRatio = true;
    private String initialScreenId = "";

    private ImageListWidget imageListWidget;
    private Button selectButton;
    private Button uploadFromComputerButton;
    private Button downloadFromUrlButton;
    private Button galleryTabButton;
    private Button settingsTabButton;
    private Button addImageButton;
    private Button backButton;
    private Checkbox maintainAspectCheckbox;
    private EditBox searchBar;
    private EditBox screenIdField;
    private EditBox urlField;
    private Button linkScreenIdButton;

    private enum Tab { GALLERY, SETTINGS, IMPORT }
    private Tab currentTab = Tab.GALLERY;


    public ImageLoadScreen(BlockPos blockEntityPos) {
        super(Component.translatable("gui.simplyscreens.screen.title"));
        this.blockEntityPos = blockEntityPos;
    }

    @Override
    protected void init() {
        super.init();
        fetchDataFromBlockEntity();

        int guiLeft = (this.width - SCREEN_WIDTH) / 2;
        int guiTop = (this.height - SCREEN_HEIGHT) / 2;

        // Tab buttons at the top (only Gallery and Settings)
        galleryTabButton = Button.builder(Component.literal("Gallery"), button -> switchTab(Tab.GALLERY))
                .pos(guiLeft + 8, guiTop + 20)
                .size(78, 18)
                .build();
        addRenderableWidget(galleryTabButton);

        settingsTabButton = Button.builder(Component.literal("Settings"), button -> switchTab(Tab.SETTINGS))
                .pos(guiLeft + 89, guiTop + 20)
                .size(78, 18)
                .build();
        addRenderableWidget(settingsTabButton);

        // Add Image button (only visible in Gallery tab, same height as search bar)
        addImageButton = Button.builder(Component.literal("+"), button -> switchTab(Tab.IMPORT))
                .pos(guiLeft + 150, guiTop + 66)
                .size(18, 18)
                .build();
        addRenderableWidget(addImageButton);

        // Back button (only visible in Import tab)
        backButton = Button.builder(Component.literal("← Back"), button -> switchTab(Tab.GALLERY))
                .pos(guiLeft + 8, guiTop + 45)
                .size(50, 18)
                .build();
        addRenderableWidget(backButton);

        // Gallery Tab Components
        searchBar = new EditBox(this.font, guiLeft + 8, guiTop + 66, 138, 18, Component.translatable("gui.simplyscreens.screen.search.placeholder"));
        searchBar.setResponder(searchTerm -> {
            if (this.imageListWidget != null) {
                this.imageListWidget.filter(searchTerm);
            }
        });
        addRenderableWidget(searchBar);

        imageListWidget = new ImageListWidget(guiLeft + 8, guiTop + 88, 160, 90, Component.literal(""), this::onImageSelected, initialLocalHash);
        addRenderableWidget(imageListWidget);

        selectButton = Button.builder(Component.translatable("gui.simplyscreens.screen.select"), button -> onSelect())
                .pos(guiLeft + 8, guiTop + 182)
                .size(160, 20)
                .build();
        selectButton.active = false;
        addRenderableWidget(selectButton);

        // Settings Tab Components
        screenIdField = new EditBox(this.font, guiLeft + 8, guiTop + 55, 100, 18, Component.translatable("gui.simplyscreens.screen.id.placeholder"));
        screenIdField.setValue(initialScreenId);
        screenIdField.setTooltip(Tooltip.create(Component.translatable("gui.simplyscreens.screen.id.tooltip")));
        addRenderableWidget(screenIdField);

        linkScreenIdButton = Button.builder(Component.translatable("gui.simplyscreens.screen.link"), button -> onLinkScreenId())
                .pos(guiLeft + 110, guiTop + 55)
                .size(58, 18)
                .build();
        addRenderableWidget(linkScreenIdButton);

        maintainAspectCheckbox = Checkbox.builder(Component.translatable("gui.simplyscreens.screen.maintain_aspect"), this.font)
                .pos(guiLeft + 8, guiTop + 80)
                .selected(this.initialMaintainAspectRatio)
                .onValueChange((checkbox, selected) -> {
                    if (minecraft != null && minecraft.level != null) {
                        BlockEntity blockEntity = minecraft.level.getBlockEntity(blockEntityPos);
                        if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                            ScreenBlockEntity anchor = screenBlockEntity.getAnchorEntity();
                            if (anchor != null) {
                                PacketRegistries.sendToServer(new UpdateScreenAspectRatioC2SPacket(anchor.getBlockPos(), selected));
                            }
                        }
                    }
                })
                .build();
        addRenderableWidget(maintainAspectCheckbox);

        // Import Tab Components - Upload from Computer Section
        uploadFromComputerButton = Button.builder(Component.literal("Upload"), button -> onUploadFromComputer())
                .pos(guiLeft + 8, guiTop + 80)
                .size(160, 24)
                .build();
        uploadFromComputerButton.visible = !Config.DISABLE_UPLOAD;
        addRenderableWidget(uploadFromComputerButton);

        // Import Tab Components - Download from URL Section
        urlField = new EditBox(this.font, guiLeft + 8, guiTop + 130, 120, 18, Component.translatable("gui.simplyscreens.screen.url.placeholder"));
        urlField.setMaxLength(2048); // URLs can be very long
        urlField.setTooltip(Tooltip.create(Component.translatable("gui.simplyscreens.screen.url.tooltip")));
        addRenderableWidget(urlField);

        downloadFromUrlButton = Button.builder(Component.literal("Load"), button -> onDownloadFromUrl())
                .pos(guiLeft + 130, guiTop + 130)
                .size(38, 18)
                .build();
        downloadFromUrlButton.visible = !Config.DISABLE_URL_DOWNLOAD;
        addRenderableWidget(downloadFromUrlButton);

        updateTabVisibility();
        setInitialFocus(searchBar);
        imageListWidget.refresh();
    }

    private void switchTab(Tab tab) {
        this.currentTab = tab;
        updateTabVisibility();
    }

    private void updateTabVisibility() {
        boolean isGallery = currentTab == Tab.GALLERY;
        boolean isSettings = currentTab == Tab.SETTINGS;
        boolean isImport = currentTab == Tab.IMPORT;

        // Gallery tab
        searchBar.visible = isGallery;
        imageListWidget.visible = isGallery;
        selectButton.visible = isGallery;
        addImageButton.visible = isGallery;

        // Settings tab
        screenIdField.visible = isSettings;
        linkScreenIdButton.visible = isSettings;
        maintainAspectCheckbox.visible = isSettings;

        // Import tab
        uploadFromComputerButton.visible = isImport && !Config.DISABLE_UPLOAD;
        urlField.visible = isImport && !Config.DISABLE_URL_DOWNLOAD;
        downloadFromUrlButton.visible = isImport && !Config.DISABLE_URL_DOWNLOAD;
        backButton.visible = isImport;

        // Update tab button states (visual feedback)
        galleryTabButton.active = !isGallery && !isImport; // Also inactive on Import
        settingsTabButton.active = !isSettings;
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

    private void onLinkScreenId() {
        String screenId = screenIdField.getValue();
        ImageListWidget.ImageEntry selectedEntry = imageListWidget.getSelected();

        if (screenId == null || screenId.isEmpty()) {
            return;
        }

        if (selectedEntry == null) {
            // Just update the screen ID without changing the image
            if (this.minecraft != null && this.minecraft.level != null) {
                BlockEntity blockEntity = this.minecraft.level.getBlockEntity(blockEntityPos);
                if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                    ScreenBlockEntity anchor = screenBlockEntity.getAnchorEntity();
                    if (anchor != null) {
                        PacketRegistries.sendToServer(new UpdateScreenIdC2SPacket(anchor.getBlockPos(), screenId));
                    }
                }
            }
        } else {
            // Update both screen ID and image
            if (this.minecraft != null && this.minecraft.level != null) {
                BlockEntity blockEntity = this.minecraft.level.getBlockEntity(blockEntityPos);
                if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                    ScreenBlockEntity anchor = screenBlockEntity.getAnchorEntity();
                    if (anchor != null) {
                        // First set the image, then set the screen ID (which will register the mapping)
                        PacketRegistries.sendToServer(new UpdateScreenSelectedImageC2SPacket(anchor.getBlockPos(), selectedEntry.getImageId()));
                        PacketRegistries.sendToServer(new UpdateScreenIdC2SPacket(anchor.getBlockPos(), screenId));
                    }
                }
            }
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

                        PacketRegistries.sendToServer(new UploadImageChunkC2SPacket(blockEntityPos, transactionId, i, totalChunks, chunk, i == 0 ? fileName : null));
                    }

                    // Refresh and auto-switch to Gallery tab after upload
                    imageListWidget.refresh();
                    switchTab(Tab.GALLERY);
                } catch (java.io.IOException e) {
                    SimplyScreens.LOGGER.error("Failed to read image file", e);
                }
            }
        }
    }

    private void onDownloadFromUrl() {
        String url = urlField.getValue();

        if (url == null || url.isEmpty()) {
            return;
        }

        // Basic validation
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            TinyFileDialogs.tinyfd_messageBox(
                Component.translatable("gui.simplyscreens.screen.dialog.upload_error").getString(),
                Component.translatable("gui.simplyscreens.screen.url.error.invalid").getString(),
                "ok", "error", true);
            return;
        }

        // Extract filename from URL or use default
        String fileName = "downloaded_image.png";
        try {
            java.net.URI uri = new java.net.URI(url);
            String path = uri.getPath();
            if (path != null && path.contains(".")) {
                int lastSlash = path.lastIndexOf('/');
                if (lastSlash >= 0 && lastSlash < path.length() - 1) {
                    fileName = path.substring(lastSlash + 1);
                }
            }
        } catch (Exception e) {
            // Use default filename
        }

        // Send download request to server
        PacketRegistries.sendToServer(new DownloadImageFromUrlC2SPacket(blockEntityPos, url, fileName));

        // Refresh image list after a delay (server will send updated list)
        new Thread(() -> {
            try {
                Thread.sleep(2000); // Wait for download to complete
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (minecraft != null) {
                minecraft.execute(() -> {
                    imageListWidget.refresh();
                    switchTab(Tab.GALLERY);
                });
            }
        }).start();
    }


    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int guiLeft = (this.width - SCREEN_WIDTH) / 2;
        int guiTop = (this.height - SCREEN_HEIGHT) / 2;
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // Title
        guiGraphics.drawString(this.font, this.title, (this.width - this.font.width(this.title)) / 2, guiTop + 6, 0x404040, false);

        // Tab labels based on current tab
        if (currentTab == Tab.SETTINGS) {
            guiGraphics.drawString(this.font, Component.translatable("gui.simplyscreens.screen.id.label"), guiLeft + 8, guiTop + 55, 0x404040, false);
        } else if (currentTab == Tab.IMPORT) {
            // Upload from Computer section label
            guiGraphics.drawString(this.font, Component.literal("From Computer:"), guiLeft + 8, guiTop + 68, 0x404040, false);
            // Download from URL section label
            guiGraphics.drawString(this.font, Component.literal("From URL:"), guiLeft + 8, guiTop + 118, 0x404040, false);
        }
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int i, int j, float f) {
        super.renderBackground(guiGraphics, i, j, f);
        int guiLeft = (this.width - SCREEN_WIDTH) / 2;
        int guiTop = (this.height - SCREEN_HEIGHT) / 2;
        guiGraphics.blit(BACKGROUND_TEXTURE, guiLeft, guiTop, 0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
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
                    initialScreenId = anchor.getScreenId() != null ? anchor.getScreenId() : "";
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
                        PacketRegistries.sendToServer(new UpdateScreenSelectedImageC2SPacket(anchor.getBlockPos(), selectedEntry.getImageId()));
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
    
    private boolean isSupportedImageFormat(String filePath) {
        if (filePath == null) return false;

        String lowerFilePath = filePath.toLowerCase();
        return lowerFilePath.endsWith(".png") ||
               lowerFilePath.endsWith(".jpg") ||
               lowerFilePath.endsWith(".jpeg");
    }
}