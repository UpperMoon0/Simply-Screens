package com.nstut.simplyscreens.client.screens;

import com.nstut.simplyscreens.client.ClientServerConfig;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.ImageImportSupport;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.client.ScreenGuiConstants;
import com.nstut.simplyscreens.client.gui.widgets.ImageListWidget;
import com.nstut.simplyscreens.network.DownloadImageFromUrlC2SPacket;
import com.nstut.simplyscreens.network.PacketRegistries;
import com.nstut.simplyscreens.network.UpdateScreenIdC2SPacket;
import com.nstut.simplyscreens.network.UploadImageChunkC2SPacket;
import com.nstut.simplyscreens.network.UpdateScreenAspectRatioC2SPacket;
import com.nstut.simplyscreens.network.UpdateScreenSelectedImageC2SPacket;
import com.nstut.simplyscreens.network.RemoveImageC2SPacket;
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
    private static final ResourceLocation BACKGROUND_TEXTURE = new ResourceLocation(SimplyScreens.MOD_ID, "textures/gui/screen.png");

    private static final int CHUNK_SIZE = com.nstut.simplyscreens.helpers.ChunkedFileTransfer.CHUNK_SIZE;
    private static final int SCREEN_WIDTH = ScreenGuiConstants.SCREEN_WIDTH;
    private static final int SCREEN_HEIGHT = ScreenGuiConstants.SCREEN_HEIGHT;

    private final BlockPos blockEntityPos;
    private java.util.UUID initialLocalHash;
    private String initialScreenId = "";
    private boolean initialMaintainAspectRatio = true;

    private ImageListWidget imageListWidget;
    private Button selectButton;
    private Button removeButton;
    private Button uploadFromComputerButton;
    private Button downloadFromUrlButton;
    private Button galleryTabButton;
    private Button settingsTabButton;
    private Button addImageButton;
    private Button backButton;
    private Button linkScreenIdButton;
    private Checkbox maintainAspectCheckbox;
    private EditBox searchBar;
    private EditBox screenIdField;
    private EditBox urlField;

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

        // Tab buttons at the top (Import is reached through the + button).
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

        addImageButton = Button.builder(Component.literal("+"), button -> switchTab(Tab.IMPORT))
                .pos(guiLeft + 150, guiTop + ScreenGuiConstants.SEARCH_BAR_Y)
                .size(18, ScreenGuiConstants.BUTTON_HEIGHT)
                .tooltip(Tooltip.create(Component.translatable("gui.simplyscreens.screen.add_image.tooltip")))
                .build();
        addRenderableWidget(addImageButton);

        backButton = Button.builder(Component.literal("← Back"), button -> switchTab(Tab.GALLERY))
                .pos(guiLeft + ScreenGuiConstants.MARGIN_X, guiTop + ScreenGuiConstants.SCREEN_ID_LABEL_Y)
                .size(ScreenGuiConstants.REMOVE_BUTTON_WIDTH, ScreenGuiConstants.BUTTON_HEIGHT)
                .build();
        addRenderableWidget(backButton);

        // Gallery Tab Components (y starts at 25)
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

        maintainAspectCheckbox = new Checkbox(guiLeft + ScreenGuiConstants.MARGIN_X, guiTop + ScreenGuiConstants.MAINTAIN_ASPECT_CHECKBOX_Y, 20, 20, Component.empty(), this.initialMaintainAspectRatio) {
            @Override
            public void onPress() {
                super.onPress();
                if (minecraft != null && minecraft.level != null) {
                    BlockEntity blockEntity = minecraft.level.getBlockEntity(blockEntityPos);
                    if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                        ScreenBlockEntity anchor = screenBlockEntity.getAnchorEntity();
                        if (anchor != null) {
                            PacketRegistries.sendToServer(new UpdateScreenAspectRatioC2SPacket(blockEntityPos, this.selected()));
                        }
                    }
                }
            }
        };
        maintainAspectCheckbox.setTooltip(Tooltip.create(Component.translatable("gui.simplyscreens.screen.maintain_aspect.tooltip")));
        addRenderableWidget(maintainAspectCheckbox);

        // Import Tab Components
        uploadFromComputerButton = Button.builder(Component.literal("Upload"), button -> onUploadFromComputer())
                .pos(guiLeft + ScreenGuiConstants.MARGIN_X, guiTop + 80)
                .size(ScreenGuiConstants.IMAGE_LIST_WIDTH, ScreenGuiConstants.SELECT_BUTTON_HEIGHT + 4)
                .build();
        uploadFromComputerButton.visible = !ClientServerConfig.disableUpload();
        addRenderableWidget(uploadFromComputerButton);

        urlField = new EditBox(this.font, guiLeft + ScreenGuiConstants.MARGIN_X, guiTop + 130, 120, ScreenGuiConstants.URL_FIELD_HEIGHT, Component.translatable("gui.simplyscreens.screen.url.placeholder"));
        urlField.setMaxLength(2048); // URLs can be very long
        urlField.setTooltip(Tooltip.create(Component.translatable("gui.simplyscreens.screen.url.tooltip")));
        addRenderableWidget(urlField);

        downloadFromUrlButton = Button.builder(Component.literal("Load"), button -> onDownloadFromUrl())
                .pos(guiLeft + ScreenGuiConstants.DOWNLOAD_BUTTON_X, guiTop + 130)
                .size(ScreenGuiConstants.DOWNLOAD_BUTTON_WIDTH, ScreenGuiConstants.URL_FIELD_HEIGHT)
                .build();
        downloadFromUrlButton.visible = !ClientServerConfig.disableUrlDownload();
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

        // Upload tab
        uploadFromComputerButton.visible = isImport && !ClientServerConfig.disableUpload();
        urlField.visible = isImport && !ClientServerConfig.disableUrlDownload();
        downloadFromUrlButton.visible = isImport && !ClientServerConfig.disableUrlDownload();
        backButton.visible = isImport;

        // Update tab button states (visual feedback)
        galleryTabButton.active = !isGallery && !isImport;
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
        if (screenId == null) screenId = "";
        if (this.minecraft == null || this.minecraft.level == null) {
            return;
        }

        BlockEntity blockEntity = this.minecraft.level.getBlockEntity(blockEntityPos);
        if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
            ScreenBlockEntity anchor = screenBlockEntity.getAnchorEntity();
            if (anchor != null) {
                ImageListWidget.ImageEntry selectedEntry = imageListWidget.getSelected();
                UUID selectedId = selectedEntry != null ? selectedEntry.getImageId() : null;
                PacketRegistries.sendToServer(new UpdateScreenIdC2SPacket(blockEntityPos, screenId, selectedId));
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
                    if (fileSize > ClientServerConfig.maxUploadSize()) {
                        TinyFileDialogs.tinyfd_messageBox(Component.translatable("gui.simplyscreens.screen.dialog.upload_error").getString(), Component.translatable("gui.simplyscreens.screen.upload.error.size", ClientServerConfig.maxUploadSize() / 1024 / 1024).getString(), "ok", "error", true);
                        return;
                    }
                    byte[] data = java.nio.file.Files.readAllBytes(path);

                    if (data.length > ClientServerConfig.maxUploadSize()) {
                        TinyFileDialogs.tinyfd_messageBox(Component.translatable("gui.simplyscreens.screen.dialog.upload_error").getString(), Component.translatable("gui.simplyscreens.screen.upload.error.size", ClientServerConfig.maxUploadSize() / 1024 / 1024).getString(), "ok", "error", true);
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
                    imageListWidget.refresh();
                    switchTab(Tab.GALLERY);
                } catch (java.io.IOException | SecurityException e) {
                    SimplyScreens.LOGGER.error("Failed to read image file", e);
                }
            }
        }
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int guiLeft = (this.width - SCREEN_WIDTH) / 2;
        int guiTop = (this.height - SCREEN_HEIGHT) / 2;
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // Title
        guiGraphics.drawString(this.font, this.title, (this.width - this.font.width(this.title)) / 2, guiTop + 6, 0x404040, false);

        // Tab labels based on current tab
        if (currentTab == Tab.SETTINGS) {
            guiGraphics.drawString(this.font, Component.translatable("gui.simplyscreens.screen.id.label"), guiLeft + ScreenGuiConstants.MARGIN_X, guiTop + ScreenGuiConstants.SCREEN_ID_LABEL_Y, 0x404040, false);
            guiGraphics.drawString(this.font, Component.translatable("gui.simplyscreens.screen.maintain_aspect"), guiLeft + ScreenGuiConstants.MAINTAIN_ASPECT_LABEL_X, guiTop + 82, 0x404040, false);
        } else if (currentTab == Tab.IMPORT) {
            guiGraphics.drawString(this.font, Component.literal("From Computer:"), guiLeft + ScreenGuiConstants.MARGIN_X, guiTop + 68, 0x404040, false);
            guiGraphics.drawString(this.font, Component.literal("From URL:"), guiLeft + ScreenGuiConstants.MARGIN_X, guiTop + 118, 0x404040, false);
        }
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics) {
        super.renderBackground(guiGraphics);
        int guiLeft = (this.width - SCREEN_WIDTH) / 2;
        int guiTop = (this.height - SCREEN_HEIGHT) / 2;
        guiGraphics.blit(BACKGROUND_TEXTURE, guiLeft, guiTop, 0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
    }

    @Override
    public void tick() {
        super.tick();
        searchBar.tick();
        screenIdField.tick();
        urlField.tick();
        imageListWidget.tick();
    }

    private void fetchDataFromBlockEntity() {
        if (this.minecraft != null && this.minecraft.level != null) {
            BlockEntity blockEntity = this.minecraft.level.getBlockEntity(blockEntityPos);
            if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                ScreenBlockEntity anchor = screenBlockEntity.getAnchorEntity();
                if (anchor != null) {
                    initialLocalHash = anchor.getImageId();
                    initialScreenId = anchor.getScreenId() != null ? anchor.getScreenId() : "";
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
                        PacketRegistries.sendToServer(new UpdateScreenSelectedImageC2SPacket(blockEntityPos, selectedEntry.getImageId()));
                    }
                }
            }
        }

    }

    private void onDownloadFromUrl() {
        String url = urlField.getValue();
        if (url == null || url.isEmpty()) {
            return;
        }

        if (!ImageImportSupport.isHttpUrl(url)) {
            TinyFileDialogs.tinyfd_messageBox(
                    Component.translatable("gui.simplyscreens.screen.dialog.upload_error").getString(),
                    Component.translatable("gui.simplyscreens.screen.url.error.invalid").getString(),
                    "ok", "error", true);
            return;
        }

        String fileName = ImageImportSupport.fileNameFromUrl(url);

        PacketRegistries.sendToServer(new DownloadImageFromUrlC2SPacket(blockEntityPos, url, fileName));

        new Thread(() -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (minecraft != null) {
                minecraft.execute(() -> {
                    imageListWidget.refresh();
                    switchTab(Tab.GALLERY);
                });
            }
        }, "SimplyScreens URL refresh").start();
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
