package com.nstut.simplyscreens.client.screens;

import com.nstut.simplyscreens.Config;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.client.ScreenGuiConstants;
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

    private static final int CHUNK_SIZE = 1024 * 30; // 30KB
    private static final int SCREEN_WIDTH = ScreenGuiConstants.SCREEN_WIDTH;
    private static final int SCREEN_HEIGHT = ScreenGuiConstants.SCREEN_HEIGHT;

    private final BlockPos blockEntityPos;
    private java.util.UUID initialLocalHash;
    private String initialScreenId;
    private boolean initialMaintainAspectRatio = true;

    private ImageListWidget imageListWidget;
    private Button selectButton;
    private Button uploadFromComputerButton;
    private Button downloadUrlButton;
    private Button goButton;
    private Button galleryTabButton;
    private Button settingsTabButton;
    private Button uploadTabButton;
    private Checkbox maintainAspectCheckbox;
    private EditBox searchBar;
    private EditBox screenIdField;
    private EditBox urlField;

    private enum Tab { GALLERY, SETTINGS, UPLOAD }
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

        // Tab buttons at the top
        galleryTabButton = Button.builder(Component.literal("Gallery"), button -> switchTab(Tab.GALLERY))
                .pos(guiLeft + ScreenGuiConstants.MARGIN_X, guiTop + ScreenGuiConstants.TAB_BUTTON_Y)
                .size(50, ScreenGuiConstants.BUTTON_HEIGHT)
                .build();
        addRenderableWidget(galleryTabButton);

        settingsTabButton = Button.builder(Component.literal("Settings"), button -> switchTab(Tab.SETTINGS))
                .pos(guiLeft + 59, guiTop + ScreenGuiConstants.TAB_BUTTON_Y)
                .size(50, ScreenGuiConstants.BUTTON_HEIGHT)
                .build();
        addRenderableWidget(settingsTabButton);

        uploadTabButton = Button.builder(Component.literal("Upload"), button -> switchTab(Tab.UPLOAD))
                .pos(guiLeft + 110, guiTop + ScreenGuiConstants.TAB_BUTTON_Y)
                .size(50, ScreenGuiConstants.BUTTON_HEIGHT)
                .tooltip(Tooltip.create(Component.translatable("gui.simplyscreens.screen.add_image.tooltip")))
                .build();
        addRenderableWidget(uploadTabButton);

        // Gallery Tab Components (y starts at 25)
        searchBar = new EditBox(this.font, guiLeft + ScreenGuiConstants.MARGIN_X, guiTop + ScreenGuiConstants.SEARCH_BAR_Y, 160, ScreenGuiConstants.BUTTON_HEIGHT, Component.translatable("gui.simplyscreens.screen.search.placeholder"));
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

        // Settings Tab Components
        screenIdField = new EditBox(this.font, guiLeft + ScreenGuiConstants.MARGIN_X, guiTop + ScreenGuiConstants.SCREEN_ID_FIELD_Y, 160, ScreenGuiConstants.BUTTON_HEIGHT, Component.translatable("gui.simplyscreens.screen.id.placeholder"));
        screenIdField.setValue(initialScreenId != null ? initialScreenId : "");
        addRenderableWidget(screenIdField);

        maintainAspectCheckbox = new Checkbox(guiLeft + ScreenGuiConstants.MARGIN_X, guiTop + ScreenGuiConstants.MAINTAIN_ASPECT_CHECKBOX_Y, 20, 20, Component.empty(), this.initialMaintainAspectRatio) {
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
        maintainAspectCheckbox.setTooltip(Tooltip.create(Component.translatable("gui.simplyscreens.screen.maintain_aspect.tooltip")));
        addRenderableWidget(maintainAspectCheckbox);

        // Upload Tab Components
        uploadFromComputerButton = Button.builder(Component.translatable("gui.simplyscreens.screen.upload"), button -> onUploadFromComputer())
                .pos(guiLeft + 8, guiTop + 55)
                .size(160, 20)
                .build();
        uploadFromComputerButton.visible = !Config.DISABLE_UPLOAD;
        addRenderableWidget(uploadFromComputerButton);

        urlField = new EditBox(this.font, guiLeft + 8, guiTop + 85, 120, 18, Component.translatable("gui.simplyscreens.screen.url.placeholder"));
        urlField.setMaxLength(2048); // URLs can be very long
        addRenderableWidget(urlField);

        downloadUrlButton = Button.builder(Component.literal("Go"), button -> onDownloadFromUrl())
                .pos(guiLeft + 130, guiTop + 85)
                .size(38, 18)
                .build();
        downloadUrlButton.visible = !Config.DISABLE_URL_DOWNLOAD;
        addRenderableWidget(downloadUrlButton);

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
        boolean isUpload = currentTab == Tab.UPLOAD;

        // Gallery tab
        searchBar.visible = isGallery;
        imageListWidget.visible = isGallery;
        selectButton.visible = isGallery;

        // Settings tab
        screenIdField.visible = isSettings;
        maintainAspectCheckbox.visible = isSettings;

        // Upload tab
        uploadFromComputerButton.visible = isUpload && !Config.DISABLE_UPLOAD;
        urlField.visible = isUpload && !Config.DISABLE_URL_DOWNLOAD;
        downloadUrlButton.visible = isUpload && !Config.DISABLE_URL_DOWNLOAD;

        // Update tab button states (visual feedback)
        galleryTabButton.active = !isGallery;
        settingsTabButton.active = !isSettings;
        uploadTabButton.active = !isUpload;
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
        int guiTop = (this.height - SCREEN_HEIGHT) / 2;
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // Title
        guiGraphics.drawString(this.font, this.title, (this.width - this.font.width(this.title)) / 2, guiTop + 6, 0x404040, false);

        // Tab labels based on current tab
        if (currentTab == Tab.SETTINGS) {
            guiGraphics.drawString(this.font, Component.translatable("gui.simplyscreens.screen.id.label"), guiLeft + 8, guiTop + ScreenGuiConstants.SCREEN_ID_LABEL_Y, 0x404040, false);
            guiGraphics.drawString(this.font, Component.translatable("gui.simplyscreens.screen.maintain_aspect"), guiLeft + ScreenGuiConstants.MAINTAIN_ASPECT_LABEL_X, guiTop + 82, 0x404040, false);
        } else if (currentTab == Tab.UPLOAD) {
            guiGraphics.drawString(this.font, Component.translatable("gui.simplyscreens.screen.url.label"), guiLeft + 8, guiTop + 75, 0x404040, false);
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

    private void onDownloadFromUrl() {
        if (urlField.getValue().isEmpty()) {
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