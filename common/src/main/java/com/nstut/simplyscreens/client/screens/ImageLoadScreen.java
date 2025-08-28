package com.nstut.simplyscreens.client.screens;

import com.nstut.simplyscreens.Config;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.client.gui.widgets.ImageListWidget;
import com.nstut.simplyscreens.network.PacketRegistries;
import com.nstut.simplyscreens.network.UploadImageChunkC2SPacket;
import com.nstut.simplyscreens.network.UpdateScreenAspectRatioC2SPacket;
import com.nstut.simplyscreens.network.UpdateScreenSelectedImageC2SPacket;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
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
import java.util.Iterator;
import java.util.UUID;

public class ImageLoadScreen extends Screen {
    private static final int CHUNK_SIZE = 1024 * 30; // 30KB
    private static final ResourceLocation BACKGROUND_TEXTURE = new ResourceLocation(SimplyScreens.MOD_ID, "textures/gui/screen.png");

    private static final int SCREEN_WIDTH = 162;
    private static final int SCREEN_HEIGHT = 188;

    private final BlockPos blockEntityPos;
    private java.util.UUID initialLocalHash;
    private boolean initialMaintainAspectRatio = true;

    private ImageListWidget imageListWidget;
    private Button selectButton;
    private Button uploadFromComputerButton;
    private Checkbox maintainAspectCheckbox;
    private EditBox searchBar;


    public ImageLoadScreen(BlockPos blockEntityPos) {
        super(Component.literal("Screen"));
        this.blockEntityPos = blockEntityPos;
    }

    @Override
    protected void init() {
        super.init();
        fetchDataFromBlockEntity();

        int guiLeft = (this.width - SCREEN_WIDTH) / 2;
        int guiTop = (this.height - SCREEN_HEIGHT) / 2;

        searchBar = new EditBox(this.font, guiLeft + 8, guiTop + 23, 145, 20, Component.literal("Search"));
        searchBar.setResponder(searchTerm -> {
            if (this.imageListWidget != null) {
                this.imageListWidget.filter(searchTerm);
            }
        });
        addRenderableWidget(searchBar);

        imageListWidget = new ImageListWidget(guiLeft + 8, guiTop + 47, 145, 60, Component.literal(""), this::onImageSelected, initialLocalHash);
        addRenderableWidget(imageListWidget);

        maintainAspectCheckbox = Checkbox.builder(Component.literal("Maintain Aspect Ratio"), this.font)
                .pos(guiLeft + 8, guiTop + 112)
                .selected(this.initialMaintainAspectRatio)
                .onValueChange((checkbox, selected) -> {
                    if (minecraft != null && minecraft.level != null) {
                        BlockEntity blockEntity = minecraft.level.getBlockEntity(blockEntityPos);
                        if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                            ScreenBlockEntity anchor = screenBlockEntity.getAnchorEntity();
                            if (anchor != null) {
                                PacketRegistries.CHANNEL.sendToServer(new UpdateScreenAspectRatioC2SPacket(anchor.getBlockPos(), selected));
                            }
                        }
                    }
                })
                .build();
        addRenderableWidget(maintainAspectCheckbox);

        selectButton = Button.builder(Component.literal("Select"), button -> onSelect())
                .pos(guiLeft + 8, guiTop + 137)
                .size(145, 20)
                .build();
        selectButton.active = false;
        addRenderableWidget(selectButton);

        uploadFromComputerButton = Button.builder(Component.literal("Upload from computer"), button -> onUploadFromComputer())
                .pos(guiLeft + 8, guiTop + 162)
                .size(145, 20)
                .build();
        uploadFromComputerButton.visible = !Config.DISABLE_UPLOAD;
        addRenderableWidget(uploadFromComputerButton);

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
            filters.put(stack.UTF8("*.png;*.jpg;*.jpeg;*.gif;*.webp"));
            filters.flip();

            String filePath = TinyFileDialogs.tinyfd_openFileDialog("Select Image", "", filters, "Image Files", false);

            if (filePath != null) {
                try {
                    Path path = Paths.get(filePath);
                    byte[] data = java.nio.file.Files.readAllBytes(path);

                    if (data.length > Config.MAX_UPLOAD_SIZE) {
                        TinyFileDialogs.tinyfd_messageBox("Upload Error", "File size exceeds the maximum allowed size of " + (Config.MAX_UPLOAD_SIZE / 1024 / 1024) + "MB.", "ok", "error", true);
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
        int guiTop = (this.height - SCREEN_HEIGHT) / 2;
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawString(this.font, this.title, (this.width - this.font.width(this.title)) / 2, guiTop + 8, 0x404040, false);
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