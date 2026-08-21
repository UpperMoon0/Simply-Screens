package com.nstut.simplyscreens.client.screens;

import com.nstut.simplyscreens.Config;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.ImageImportSupport;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.client.ScreenGuiConstants;
import com.nstut.simplyscreens.client.gui.widgets.ImageListWidget;
import com.nstut.simplyscreens.network.RemoveImageC2SPacket;
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
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

public class ImageLoadScreen extends Screen {
    private static final Identifier BACKGROUND_TEXTURE = Identifier.fromNamespaceAndPath(SimplyScreens.MOD_ID, "textures/gui/screen.png");

    private static final int CHUNK_SIZE = com.nstut.simplyscreens.helpers.ChunkedFileTransfer.CHUNK_SIZE;
    private static final int SCREEN_WIDTH = ScreenGuiConstants.SCREEN_WIDTH;
    private static final int SCREEN_HEIGHT = ScreenGuiConstants.SCREEN_HEIGHT;

    private final BlockPos blockEntityPos;
    private UUID initialLocalHash;
    private boolean initialMaintainAspectRatio = true;
    private String initialScreenId = "";

    private ImageListWidget imageListWidget;
    private Button selectButton;
    private Button removeButton;
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
                .pos(guiLeft + ScreenGuiConstants.MARGIN_X, guiTop + ScreenGuiConstants.TAB_BUTTON_Y)
                .size(78, ScreenGuiConstants.BUTTON_HEIGHT)
                .build();
        addRenderableWidget(galleryTabButton);

        settingsTabButton = Button.builder(Component.literal("Settings"), button -> switchTab(Tab.SETTINGS))
                .pos(guiLeft + 89, guiTop + ScreenGuiConstants.TAB_BUTTON_Y)
                .size(78, ScreenGuiConstants.BUTTON_HEIGHT)
                .build();
        addRenderableWidget(settingsTabButton);

        // Add Image button (only visible in Gallery tab, same height as search bar)
        addImageButton = Button.builder(Component.literal("+"), button -> switchTab(Tab.IMPORT))
                .pos(guiLeft + 150, guiTop + ScreenGuiConstants.SEARCH_BAR_Y)
                .size(18, ScreenGuiConstants.BUTTON_HEIGHT)
                .tooltip(Tooltip.create(Component.translatable("gui.simplyscreens.screen.add_image.tooltip")))
                .build();
        addRenderableWidget(addImageButton);

        // Back button (only visible in Import tab)
        backButton = Button.builder(Component.literal("← Back"), button -> switchTab(Tab.GALLERY))
                .pos(guiLeft + ScreenGuiConstants.MARGIN_X, guiTop + ScreenGuiConstants.SCREEN_ID_LABEL_Y)
                .size(ScreenGuiConstants.REMOVE_BUTTON_WIDTH, ScreenGuiConstants.BUTTON_HEIGHT)
                .build();
        addRenderableWidget(backButton);

        // Gallery Tab Components
        searchBar = new EditBox(this.font, guiLeft + ScreenGuiConstants.MARGIN_X, guiTop + ScreenGuiConstants.SEARCH_BAR_Y, 138, ScreenGuiConstants.BUTTON_HEIGHT, Component.translatable("gui.simplyscreens.screen.search.placeholder"));
        searchBar.setResponder(searchTerm -> {
            if (this.imageListWidget != null) {
                this.imageListWidget.filter(searchTerm);
            }
        });
        searchBar.setTooltip(Tooltip.create(Component.translatable("gui.simplyscreens.screen.search.tooltip")));
        addRenderableWidget(searchBar);

        imageListWidget = new ImageListWidget(guiLeft + ScreenGuiConstants.MARGIN_X, guiTop + ScreenGuiConstants.IMAGE_LIST_WIDGET_Y, ScreenGuiConstants.IMAGE_LIST_WIDTH, ScreenGuiConstants.IMAGE_LIST_HEIGHT, Component.literal(""), this::onImageSelected, initialLocalHash);
        addRenderableWidget(imageListWidget);

        selectButton = Button.builder(Component.translatable("gui.simplyscreens.screen.select"), button -> onSelect())
                .pos(guiLeft + ScreenGuiConstants.MARGIN_X, guiTop + ScreenGuiConstants.SELECT_BUTTON_Y)
                .size(ScreenGuiConstants.SELECT_BUTTON_WIDTH, ScreenGuiConstants.SELECT_BUTTON_HEIGHT)
                .build();
        selectButton.active = false;
        addRenderableWidget(selectButton);

        removeButton = Button.builder(Component.translatable("gui.simplyscreens.screen.remove"), button -> onRemove())
                .pos(guiLeft + ScreenGuiConstants.MARGIN_X + ScreenGuiConstants.SELECT_BUTTON_WIDTH + 4, guiTop + ScreenGuiConstants.SELECT_BUTTON_Y)
                .size(ScreenGuiConstants.REMOVE_BUTTON_WIDTH, ScreenGuiConstants.SELECT_BUTTON_HEIGHT)
                .tooltip(Tooltip.create(Component.translatable("gui.simplyscreens.screen.remove.tooltip")))
                .build();
        removeButton.active = false;
        addRenderableWidget(removeButton);

        // Settings Tab Components
        screenIdField = new EditBox(this.font, guiLeft + ScreenGuiConstants.MARGIN_X, guiTop + ScreenGuiConstants.SCREEN_ID_FIELD_Y, 100, ScreenGuiConstants.BUTTON_HEIGHT, Component.translatable("gui.simplyscreens.screen.id.placeholder"));
        screenIdField.setValue(initialScreenId);
        screenIdField.setTooltip(Tooltip.create(Component.translatable("gui.simplyscreens.screen.id.tooltip")));
        addRenderableWidget(screenIdField);

        linkScreenIdButton = Button.builder(Component.translatable("gui.simplyscreens.screen.link"), button -> onLinkScreenId())
                .pos(guiLeft + 110, guiTop + ScreenGuiConstants.SCREEN_ID_FIELD_Y)
                .size(58, ScreenGuiConstants.BUTTON_HEIGHT)
                .build();
        addRenderableWidget(linkScreenIdButton);

        maintainAspectCheckbox = Checkbox.builder(Component.empty(), this.font)
                .pos(guiLeft + ScreenGuiConstants.MARGIN_X, guiTop + ScreenGuiConstants.MAINTAIN_ASPECT_CHECKBOX_Y)
                .selected(this.initialMaintainAspectRatio)
                .onValueChange((checkbox, selected) -> {
                    if (minecraft != null && minecraft.level != null) {
                        BlockEntity blockEntity = minecraft.level.getBlockEntity(blockEntityPos);
                        if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                            ScreenBlockEntity anchor = screenBlockEntity.getAnchorEntity();
                            if (anchor != null) {
                            PacketRegistries.sendToServer(new UpdateScreenAspectRatioC2SPacket(blockEntityPos, selected));
                            }
                        }
                    }
                })
                .build();
        maintainAspectCheckbox.setTooltip(Tooltip.create(Component.translatable("gui.simplyscreens.screen.maintain_aspect.tooltip")));
        addRenderableWidget(maintainAspectCheckbox);

        // Import Tab Components - Upload from Computer Section
        uploadFromComputerButton = Button.builder(Component.literal("Upload"), button -> onUploadFromComputer())
                .pos(guiLeft + ScreenGuiConstants.MARGIN_X, guiTop + 80)
                .size(ScreenGuiConstants.IMAGE_LIST_WIDTH, ScreenGuiConstants.SELECT_BUTTON_HEIGHT + 4)
                .build();
        uploadFromComputerButton.visible = !Config.DISABLE_UPLOAD;
        addRenderableWidget(uploadFromComputerButton);

        // Import Tab Components - Download from URL Section
        urlField = new EditBox(this.font, guiLeft + ScreenGuiConstants.MARGIN_X, guiTop + 130, 120, ScreenGuiConstants.URL_FIELD_HEIGHT, Component.translatable("gui.simplyscreens.screen.url.placeholder"));
        urlField.setMaxLength(2048); // URLs can be very long
        urlField.setTooltip(Tooltip.create(Component.translatable("gui.simplyscreens.screen.url.tooltip")));
        addRenderableWidget(urlField);

        downloadFromUrlButton = Button.builder(Component.literal("Load"), button -> onDownloadFromUrl())
                .pos(guiLeft + ScreenGuiConstants.DOWNLOAD_BUTTON_X, guiTop + 130)
                .size(ScreenGuiConstants.DOWNLOAD_BUTTON_WIDTH, ScreenGuiConstants.URL_FIELD_HEIGHT)
                .build();
        downloadFromUrlButton.visible = !Config.DISABLE_URL_DOWNLOAD;
        addRenderableWidget(downloadFromUrlButton);

        updateTabVisibility();
        setInitialFocus(searchBar);
        imageListWidget.refresh();
    }

    private void onRemove() {
        ImageListWidget.ImageEntry selectedEntry = imageListWidget.getSelected();
        if (selectedEntry != null) {
            PacketRegistries.sendToServer(new RemoveImageC2SPacket(selectedEntry.getImageId()));
        }
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
        removeButton.visible = isGallery;
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
        removeButton.active = entry != null;
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
                    PacketRegistries.sendToServer(new UpdateScreenIdC2SPacket(blockEntityPos, screenId));
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
                        PacketRegistries.sendToServer(new UpdateScreenSelectedImageC2SPacket(blockEntityPos, selectedEntry.getImageId()));
                        PacketRegistries.sendToServer(new UpdateScreenIdC2SPacket(blockEntityPos, screenId));
                    }
                }
            }
        }
    }

    private void onUploadFromComputer() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(3);
            filters.put(stack.UTF8("*.png"));
            filters.put(stack.UTF8("*.jpg"));
            filters.put(stack.UTF8("*.jpeg"));
            filters.flip();

            String filePath = TinyFileDialogs.tinyfd_openFileDialog(Component.translatable("gui.simplyscreens.screen.dialog.select_image").getString(), "", filters, Component.translatable("gui.simplyscreens.screen.dialog.image_files").getString(), false);

            if (filePath != null) {
                try {
                    Path path = Paths.get(filePath);
                    long fileSize = java.nio.file.Files.size(path);
                    if (fileSize > Config.MAX_UPLOAD_SIZE) {
                        TinyFileDialogs.tinyfd_messageBox(Component.translatable("gui.simplyscreens.screen.dialog.upload_error").getString(), Component.translatable("gui.simplyscreens.screen.upload.error.size", Config.MAX_UPLOAD_SIZE / 1024 / 1024).getString(), "ok", "error", 1);
                        return;
                    }
                    byte[] data = java.nio.file.Files.readAllBytes(path);

                    if (data.length > Config.MAX_UPLOAD_SIZE) {
                        TinyFileDialogs.tinyfd_messageBox(Component.translatable("gui.simplyscreens.screen.dialog.upload_error").getString(), Component.translatable("gui.simplyscreens.screen.upload.error.size", Config.MAX_UPLOAD_SIZE / 1024 / 1024).getString(), "ok", "error", 1);
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
        if (!ImageImportSupport.isHttpUrl(url)) {
            TinyFileDialogs.tinyfd_messageBox(
                Component.translatable("gui.simplyscreens.screen.dialog.upload_error").getString(),
                Component.translatable("gui.simplyscreens.screen.url.error.invalid").getString(),
                "ok", "error", 1);
            return;
        }

        // Extract filename from URL or use default
        String fileName = ImageImportSupport.fileNameFromUrl(url);

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
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        int guiLeft = (this.width - SCREEN_WIDTH) / 2;
        int guiTop = (this.height - SCREEN_HEIGHT) / 2;

        // Title
        guiGraphics.text(this.font, this.title, (this.width - this.font.width(this.title)) / 2, guiTop + 6, 0xFF404040, false);

        // Tab labels based on current tab
        if (currentTab == Tab.SETTINGS) {
            guiGraphics.text(this.font, Component.translatable("gui.simplyscreens.screen.id.label"), guiLeft + ScreenGuiConstants.MARGIN_X, guiTop + ScreenGuiConstants.SCREEN_ID_LABEL_Y, 0xFF404040, false);
            guiGraphics.text(this.font, Component.translatable("gui.simplyscreens.screen.maintain_aspect"), guiLeft + ScreenGuiConstants.MAINTAIN_ASPECT_LABEL_X, guiTop + 82, 0xFF404040, false);
        } else if (currentTab == Tab.IMPORT) {
            // Upload from Computer section label
            guiGraphics.text(this.font, Component.literal("From Computer:"), guiLeft + ScreenGuiConstants.MARGIN_X, guiTop + 68, 0xFF404040, false);
            // Download from URL section label
            guiGraphics.text(this.font, Component.literal("From URL:"), guiLeft + ScreenGuiConstants.MARGIN_X, guiTop + 118, 0xFF404040, false);
        }

        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor guiGraphics, int i, int j, float f) {
        super.extractBackground(guiGraphics, i, j, f);
        int guiLeft = (this.width - SCREEN_WIDTH) / 2;
        int guiTop = (this.height - SCREEN_HEIGHT) / 2;
        guiGraphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND_TEXTURE, guiLeft, guiTop, 0, 0, SCREEN_WIDTH, SCREEN_HEIGHT, 256, 256);
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
                        PacketRegistries.sendToServer(new UpdateScreenSelectedImageC2SPacket(blockEntityPos, selectedEntry.getImageId()));
                    }
                }
            }
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (this.minecraft == null) {
            return false;
        }

        if (this.searchBar.isFocused() && this.searchBar.keyPressed(event)) {
            return true;
        }

        if (this.minecraft.options.keyInventory.matches(event)) {
            this.onClose();
            return true;
        }

        return super.keyPressed(event);
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
