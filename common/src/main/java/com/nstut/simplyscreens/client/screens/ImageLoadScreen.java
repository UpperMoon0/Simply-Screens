package com.nstut.simplyscreens.client.screens;

import com.mojang.blaze3d.systems.RenderSystem;
import com.nstut.simplyscreens.DisplayMode;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.client.gui.widgets.ImageListWidget;
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
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ImageLoadScreen extends Screen {
    private static final ResourceLocation BACKGROUND_TEXTURE = new ResourceLocation(SimplyScreens.MOD_ID, "textures/gui/screen.png");

    private static final int SCREEN_WIDTH = 162;
    private static final int SCREEN_HEIGHT = 158;

    private DisplayMode currentMode = DisplayMode.INTERNET;

    private EditBox internetUrlField;
    private final BlockPos blockEntityPos;
    private String initialInternetUrl;
    private String initialLocalHash;
    private boolean initialMaintainAspectRatio = true;
    private DisplayMode initialDisplayMode = DisplayMode.INTERNET;
    private Checkbox maintainAspectCheckbox;
    private Button internetButton;
    private Button localButton;
    private ImageListWidget imageListWidget;
    private Button uploadButton;
    private Button selectButton;
    private Button uploadLocalFileButton;

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
        internetButton = Button.builder(Component.literal("Internet"), button -> updateMode(DisplayMode.INTERNET))
                .pos(guiLeft + 8, guiTop + 32)
                .size(70, 20)
                .build();

        localButton = Button.builder(Component.literal("Local"), button -> updateMode(DisplayMode.LOCAL))
                .pos(guiLeft + 83, guiTop + 32)
                .size(70, 20)
                .build();

        // Initialize the EditBox for image path input
        this.internetUrlField = new EditBox(this.font, guiLeft + 10, guiTop + 69, 140, 20, Component.literal(""));
        this.internetUrlField.setMaxLength(255);
        this.internetUrlField.setValue(initialInternetUrl);
        this.internetUrlField.setResponder(text -> sendScreenInputsToServer());

        maintainAspectCheckbox = new Checkbox(guiLeft + 10, guiTop + 131, 20, 20, Component.literal("Maintain Aspect Ratio"), initialMaintainAspectRatio) {
            @Override
            public void onPress() {
                super.onPress();
                sendScreenInputsToServer();
            }
        };

        // Create the "Load Image" button
        uploadButton = Button.builder(Component.literal("Load Image"), button -> loadImageFromUrl())
                .pos(guiLeft + 21, guiTop + 93)
                .size(120, 20)
                .build();

        selectButton = Button.builder(Component.literal("Select Image"), button -> {
                    ImageListWidget.ImageEntry selectedEntry = imageListWidget.getSelected();
                    if (selectedEntry != null) {
                        sendScreenInputsToServer();
                        imageListWidget.setDisplayedImage(selectedEntry.getImageHash());
                    }
                })
                .pos(guiLeft + 21, guiTop + 108)
                .size(120, 20)
                .build();

        uploadLocalFileButton = Button.builder(Component.literal("Upload"), button -> openFileDialog())
                .pos(guiLeft + 21, guiTop + 128)
                .size(120, 20)
                .build();

        imageListWidget = new ImageListWidget(guiLeft + 10, guiTop + 54, 140, 50, Component.literal(""), entry -> {
        });

        if (initialLocalHash != null && !initialLocalHash.isBlank()) {
            imageListWidget.setDisplayedImage(initialLocalHash);
        }

        this.addRenderableWidget(internetButton);
        this.addRenderableWidget(localButton);
        this.addRenderableWidget(this.internetUrlField);
        this.addRenderableWidget(maintainAspectCheckbox);
        this.addRenderableWidget(uploadButton);
        this.addRenderableWidget(selectButton);
        this.addRenderableWidget(uploadLocalFileButton);
        this.addRenderableWidget(imageListWidget);

        updateMode(initialDisplayMode);
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

        if (currentMode == DisplayMode.INTERNET) {
            String pathLabel = "Image URL";
            int pathLabelX = guiLeft + 12;
            pGuiGraphics.drawString(this.font, pathLabel, pathLabelX, guiTop + 59, 0x3F3F3F, false);
        } else if (currentMode == DisplayMode.LOCAL) {
            String pathLabel = "Cached Images";
            int pathLabelX = guiLeft + 12;
            pGuiGraphics.drawString(this.font, pathLabel, pathLabelX, guiTop + 59, 0x3F3F3F, false);
        }

        // Draw widgets and other components
        super.render(pGuiGraphics, pMouseX, pMouseY, pPartialTick);
    }

    private void updateMode(DisplayMode newMode) {
        currentMode = newMode;

        internetButton.active = currentMode != DisplayMode.INTERNET;
        localButton.active = currentMode != DisplayMode.LOCAL;

        internetUrlField.setVisible(currentMode == DisplayMode.INTERNET);
        internetUrlField.setEditable(currentMode == DisplayMode.INTERNET);
        maintainAspectCheckbox.visible = true;

        uploadButton.visible = currentMode == DisplayMode.INTERNET;
        selectButton.visible = currentMode == DisplayMode.LOCAL;
        uploadLocalFileButton.visible = currentMode == DisplayMode.LOCAL;

        imageListWidget.setVisible(currentMode == DisplayMode.LOCAL);

    }

    private void loadImageFromUrl() {
        String urlString = internetUrlField.getValue();
        if (urlString.isBlank() || !isHttpUrl(urlString)) {
            return;
        }

        uploadButton.active = false;
        uploadButton.setMessage(Component.literal("Loading..."));

        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(urlString);
                connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
                connection.setRequestProperty("Accept", "image/png,image/jpeg,image/gif,image/webp,image/*,*/*;q=0.8");

                String contentType = connection.getContentType();
                String extension = getExtensionFromContentType(contentType);

                String fileName;
                if (extension != null) {
                    fileName = "image" + extension;
                } else {
                    fileName = Paths.get(url.getPath()).getFileName().toString();
                    if (fileName.isEmpty() || !isSupportedExtension(fileName)) {
                        fileName = "image.png"; // Default to png if no extension or unsupported
                    }
                }

                try (InputStream in = connection.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while (-1 != (n = in.read(buf))) {
                        out.write(buf, 0, n);
                    }
                    out.close();
                    byte[] response = out.toByteArray();

                    ClientImageCache.sendImageToServer(fileName, response, blockEntityPos, maintainAspectCheckbox.selected(), DisplayMode.INTERNET, urlString, () -> {
                        minecraft.execute(() -> {
                            uploadButton.active = true;
                            uploadButton.setMessage(Component.literal("Load Image"));
                            sendScreenInputsToServer();
                        });
                    });
                }
            } catch (Exception e) {
                SimplyScreens.LOGGER.error("Failed to load image from URL", e);
                minecraft.execute(() -> {
                    uploadButton.active = true;
                    uploadButton.setMessage(Component.literal("Load Image"));
                });
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    private void openFileDialog() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(5);
            filters.put(stack.UTF8("*.png"));
            filters.put(stack.UTF8("*.jpg"));
            filters.put(stack.UTF8("*.jpeg"));
            filters.put(stack.UTF8("*.gif"));
            filters.put(stack.UTF8("*.webp"));
            filters.flip();

            String selectedFile = TinyFileDialogs.tinyfd_openFileDialog(
                    "Select Image",
                    null,
                    filters,
                    "Image Files",
                    false
            );

            if (selectedFile != null) {
                Path imagePath = Paths.get(selectedFile);
                ClientImageCache.sendImageToServer(imagePath, blockEntityPos, maintainAspectCheckbox.selected(), imageListWidget::refresh);
            }
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
                initialDisplayMode = screenBlockEntity.getDisplayMode();
                initialInternetUrl = screenBlockEntity.getInternetUrl();
                initialLocalHash = screenBlockEntity.getLocalHash();
                initialMaintainAspectRatio = screenBlockEntity.isMaintainAspectRatio();
            }
        }
    }

    private void sendScreenInputsToServer() {
        String internetUrl = internetUrlField.getValue();
        String localHash = "";
        String localExtension = "";
        boolean maintainAspectRatio = maintainAspectCheckbox.selected();

        if (currentMode == DisplayMode.LOCAL) {
            ImageListWidget.ImageEntry selectedEntry = imageListWidget.getSelected();
            if (selectedEntry != null) {
                localHash = selectedEntry.getImageHash();
                localExtension = selectedEntry.getImageExtension();
            }
        }

        PacketRegistries.CHANNEL.sendToServer(new UpdateScreenC2SPacket(blockEntityPos, currentMode, internetUrl, localHash, localExtension, maintainAspectRatio));
    }

    private boolean isHttpUrl(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    private String getExtensionFromContentType(String contentType) {
        if (contentType == null) {
            return null;
        }
        return switch (contentType.toLowerCase()) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpeg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            default -> null;
        };
    }

    private boolean isSupportedExtension(String fileName) {
        String lowerCaseFileName = fileName.toLowerCase();
        return lowerCaseFileName.endsWith(".png") ||
               lowerCaseFileName.endsWith(".jpg") ||
               lowerCaseFileName.endsWith(".jpeg") ||
               lowerCaseFileName.endsWith(".gif") ||
               lowerCaseFileName.endsWith(".webp");
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.minecraft == null) {
            return false;
        }

        // Check if the inventory key is pressed and the EditBox is NOT focused
        if (this.minecraft.options.keyInventory.matches(keyCode, scanCode) && !this.internetUrlField.isFocused()) {
            this.onClose(); // Close the screen
            return true;    // Mark the key event as handled
        }

        // Pass other keys to the parent class for normal processing
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
