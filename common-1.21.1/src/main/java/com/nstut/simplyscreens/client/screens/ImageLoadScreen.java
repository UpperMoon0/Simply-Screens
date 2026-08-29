package com.nstut.simplyscreens.client.screens;

import com.nstut.openui.api.ButtonWidget;
import com.nstut.openui.api.HStack;
import com.nstut.openui.api.UIComponent;
import com.nstut.openui.api.Ui;
import com.nstut.openui.api.VStack;
import com.nstut.openui.controls.Badge;
import com.nstut.openui.controls.Card;
import com.nstut.openui.controls.Dialog;
import com.nstut.openui.controls.EmptyState;
import com.nstut.openui.controls.IconWidget;
import com.nstut.openui.controls.Toast;
import com.nstut.openui.layout.Alignment;
import com.nstut.openui.layout.Justification;
import com.nstut.openui.overlay.OverlayHandle;
import com.nstut.openui.state.Computed;
import com.nstut.openui.state.Signal;
import com.nstut.openui.state.Signals;
import com.nstut.openui.state.Subscription;
import com.nstut.simplyscreens.ImageImportSupport;
import com.nstut.simplyscreens.ScreenRegistryHelper;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.client.ClientServerConfig;
import com.nstut.simplyscreens.client.ui.SimplyScreensUiScreen;
import com.nstut.simplyscreens.helpers.ImageMetadata;
import com.nstut.simplyscreens.helpers.ClientImageManager;
import com.nstut.simplyscreens.network.DownloadImageFromUrlC2SPacket;
import com.nstut.simplyscreens.network.PacketRegistries;
import com.nstut.simplyscreens.network.RemoveImageC2SPacket;
import com.nstut.simplyscreens.network.RequestImageListC2SPacket;
import com.nstut.simplyscreens.network.UpdateScreenAspectRatioC2SPacket;
import com.nstut.simplyscreens.network.UpdateScreenIdC2SPacket;
import com.nstut.simplyscreens.network.UpdateScreenSelectedImageC2SPacket;
import com.nstut.simplyscreens.network.UploadImageChunkC2SPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class ImageLoadScreen extends SimplyScreensUiScreen {
    private static final int CHUNK_SIZE = com.nstut.simplyscreens.helpers.ChunkedFileTransfer.CHUNK_SIZE;
    private static final int PANEL_WIDTH = 360;

    private enum Tab { GALLERY, SETTINGS, IMPORT }
    private enum GalleryState { EMPTY, NO_MATCHES, RESULTS }
    private record ImageEntry(String displayName, UUID imageId) { }
    private record ImageRow(ImageEntry image, boolean selected, boolean displayed) { }

    private final BlockPos blockEntityPos;
    private final Signal<Tab> tab = Signals.of(Tab.GALLERY);
    private final Signal<String> search = Signals.of("");
    private final Signal<List<ImageEntry>> images = Signals.of(List.of());
    private final Signal<UUID> selectedImageId = Signals.of(null);
    private final Signal<UUID> displayedImageId = Signals.of(null);
    private final Signal<String> screenId = Signals.of("");
    private final Signal<Boolean> maintainAspectRatio = Signals.of(true);
    private final Signal<String> imageUrl = Signals.of("");
    private final Signal<Component> status = Signals.of(Component.empty());

    private final Computed<List<ImageRow>> filteredImages = Signals.computed(() -> {
        String query = search.get().trim().toLowerCase(Locale.ROOT);
        UUID selected = selectedImageId.get();
        UUID displayed = displayedImageId.get();
        return images.get().stream()
                .filter(image -> query.isEmpty() || image.displayName().toLowerCase(Locale.ROOT).contains(query))
                .map(image -> new ImageRow(image, image.imageId().equals(selected), image.imageId().equals(displayed)))
                .toList();
    });
    private final Computed<GalleryState> galleryState = Signals.computed(() -> {
        if (images.get().isEmpty()) return GalleryState.EMPTY;
        return filteredImages.get().isEmpty() ? GalleryState.NO_MATCHES : GalleryState.RESULTS;
    });

    private Subscription aspectSubscription = Subscription.EMPTY;
    private boolean applyingRemoteState;

    public ImageLoadScreen(BlockPos blockEntityPos) {
        super(Component.translatable("gui.simplyscreens.screen.title"));
        this.blockEntityPos = blockEntityPos;
    }

    @Override
    protected void init() {
        aspectSubscription.close();
        fetchDataFromBlockEntity();
        super.init();
        aspectSubscription = maintainAspectRatio.subscribe(value -> {
            if (applyingRemoteState || !hasAnchor()) return;
            PacketRegistries.sendToServer(new UpdateScreenAspectRatioC2SPacket(blockEntityPos, value));
        });
        refreshImageList();
    }

    @Override
    protected UIComponent buildUI() {
        VStack content = Ui.column(
                buildHeader(),
                Ui.tabs(tab)
                        .tab(Tab.GALLERY, Component.translatable("gui.simplyscreens.tab.gallery"))
                        .tab(Tab.SETTINGS, Component.translatable("gui.simplyscreens.tab.settings"))
                        .tab(Tab.IMPORT, Component.translatable("gui.simplyscreens.tab.import")),
                Ui.switcher(tab)
                        .when(Tab.GALLERY, this::buildGallery)
                        .when(Tab.SETTINGS, this::buildSettings)
                        .when(Tab.IMPORT, this::buildImport),
                Ui.text(() -> status.get()).wrap().maxLines(2)
        ).gap(8);
        content.fillWidth();

        Card shell = Ui.card(content)
                .outlined(true)
                .elevated(true)
                .hoverable(false)
                .padding(12);
        shell.fillWidth();
        shell.maxWidth(PANEL_WIDTH);
        return Ui.padding(12, Ui.stack(shell).align(Alignment.CENTER, Alignment.CENTER));
    }

    private UIComponent buildHeader() {
        return Ui.row(
                Ui.column(
                        Ui.heading(Component.translatable("gui.simplyscreens.screen.title")),
                        Ui.text(this::displayedImageName)
                ).gap(2).flex(),
                buildThemeToggle()
        ).gap(8).align(Alignment.CENTER).justify(Justification.SPACE_BETWEEN);
    }

    private UIComponent buildGallery() {
        UIComponent searchField = Ui.textField(search)
                .placeholder(Component.translatable("gui.simplyscreens.screen.search.placeholder").getString())
                .maxLength(128)
                .flex();
        ButtonWidget refresh = Ui.button(
                Component.translatable("gui.simplyscreens.screen.refresh"), this::refreshImageList)
                .ghost().small();
        return Ui.column(
                Ui.row(searchField, refresh).gap(6).align(Alignment.CENTER),
                Ui.switcher(galleryState)
                        .when(GalleryState.EMPTY, this::buildEmptyGallery)
                        .when(GalleryState.NO_MATCHES, this::buildNoMatches)
                        .when(GalleryState.RESULTS, this::buildGalleryResults)
        ).gap(8);
    }

    private UIComponent buildEmptyGallery() {
        EmptyState empty = Ui.emptyState(Component.translatable("gui.simplyscreens.gallery.empty"));
        if (!ClientServerConfig.disableUpload() || !ClientServerConfig.disableUrlDownload()) {
            empty.action(Component.translatable("gui.simplyscreens.screen.add_image"), () -> tab.set(Tab.IMPORT));
        }
        return empty;
    }

    private UIComponent buildNoMatches() {
        return Ui.column(
                Ui.emptyState(Component.translatable("gui.simplyscreens.gallery.no_matches", search.get())),
                Ui.button(Component.translatable("gui.simplyscreens.screen.clear_search"), () -> search.set(""))
                        .ghost().small()
        ).gap(6);
    }

    private UIComponent buildGalleryResults() {
        return Ui.list(filteredImages, this::buildImageRow)
                .key(row -> row.image().imageId())
                .itemHeight(60)
                .gap(6)
                .height(Math.max(96, Math.min(210, height - 125)))
                .fillWidth();
    }

    private UIComponent buildImageRow(ImageRow row) {
        ImageEntry image = row.image();
        Card card = Ui.card().outlined(true).padding(8).selected(row.selected());
        HStack actions = Ui.row().gap(5).align(Alignment.CENTER);
        if (row.displayed()) {
            actions.child(Ui.badge(Component.translatable("gui.simplyscreens.gallery.displayed"), Badge.Variant.SUCCESS));
        } else if (row.selected()) {
            actions.child(Ui.badge(Component.translatable("gui.simplyscreens.gallery.selected"), Badge.Variant.NEUTRAL));
        }
        if (!row.selected()) {
            actions.child(Ui.button(Component.translatable("gui.simplyscreens.screen.choose"),
                    () -> selectedImageId.set(image.imageId())).secondary().small());
        } else if (!row.displayed()) {
            actions.child(Ui.button(Component.translatable("gui.simplyscreens.screen.select"), this::onSelect)
                    .primary().small());
        }
        actions.child(Ui.button(Component.translatable("gui.simplyscreens.screen.remove"),
                () -> confirmRemove(image)).danger().small());
        IconWidget thumbnail = IconWidget.custom(30,
                graphics -> ClientImageManager.renderThumbnail(graphics, image.imageId(), 30));
        UIComponent label = Ui.column(
                Ui.text(Component.literal(image.displayName())),
                Ui.text(Component.literal(image.imageId().toString()))
        ).gap(2).flex();
        card.addChild(Ui.responsive(context -> context.width() < 285
                ? Ui.column(Ui.row(thumbnail, label).gap(6).align(Alignment.CENTER), actions).gap(5)
                : Ui.row(thumbnail, label, actions).gap(8).align(Alignment.CENTER)));
        return card;
    }

    private UIComponent buildSettings() {
        UIComponent idField = Ui.textField(screenId)
                .placeholder(Component.translatable("gui.simplyscreens.screen.id.placeholder").getString())
                .maxLength(ScreenRegistryHelper.MAX_SCREEN_ID_LENGTH)
                .flex();
        ButtonWidget link = Ui.button(
                Component.translatable("gui.simplyscreens.screen.link"), this::onLinkScreenId).primary();
        Card card = Ui.card().outlined(true).padding(12);
        card.addChild(Ui.column(
                Ui.text(Component.translatable("gui.simplyscreens.screen.id.label")),
                Ui.row(idField, link).gap(6).align(Alignment.CENTER),
                Ui.divider(),
                Ui.row(
                        Ui.toggle(maintainAspectRatio),
                        Ui.text(Component.translatable("gui.simplyscreens.screen.maintain_aspect"))
                ).gap(7).align(Alignment.CENTER),
                Ui.text(Component.translatable("gui.simplyscreens.screen.maintain_aspect.help"))
                        .wrap()
                        .maxLines(3)
        ).gap(8));
        return card;
    }

    private UIComponent buildImport() {
        VStack content = Ui.column().gap(10);
        if (!ClientServerConfig.disableUpload()) {
            content.child(Ui.card(Ui.column(
                    Ui.heading(Component.translatable("gui.simplyscreens.import.computer")),
                    Ui.text(Component.translatable("gui.simplyscreens.import.computer.help")).wrap().maxLines(3),
                    Ui.button(Component.translatable("gui.simplyscreens.screen.upload"), this::onUploadFromComputer)
                            .primary()
            ).gap(6)).outlined(true).padding(12));
        }
        if (!ClientServerConfig.disableUrlDownload()) {
            content.child(Ui.card(Ui.column(
                    Ui.heading(Component.translatable("gui.simplyscreens.import.url")),
                    Ui.textField(imageUrl)
                            .placeholder(Component.translatable("gui.simplyscreens.screen.url.placeholder").getString())
                            .maxLength(2048)
                            .fillWidth(),
                    Ui.button(Component.translatable("gui.simplyscreens.screen.url.load"), this::onDownloadFromUrl)
                            .secondary()
            ).gap(6)).outlined(true).padding(12));
        }
        if (ClientServerConfig.disableUpload() && ClientServerConfig.disableUrlDownload()) {
            content.child(Ui.emptyState(Component.translatable("gui.simplyscreens.import.disabled")));
        }
        return content;
    }

    private void onSelect() {
        UUID selected = selectedImageId.get();
        if (selected == null || !hasAnchor()) return;
        PacketRegistries.sendToServer(new UpdateScreenSelectedImageC2SPacket(blockEntityPos, selected));
        displayedImageId.set(selected);
        status.set(Component.translatable("gui.simplyscreens.status.selected"));
    }

    private void onLinkScreenId() {
        if (!hasAnchor()) return;
        String normalized = ScreenRegistryHelper.normalizeScreenId(screenId.get());
        if (!screenId.get().isBlank() && normalized.isEmpty()) {
            showError(Component.translatable("gui.simplyscreens.screen.id.error.invalid"));
            return;
        }
        screenId.set(normalized);
        PacketRegistries.sendToServer(new UpdateScreenIdC2SPacket(blockEntityPos, normalized, selectedImageId.get()));
        status.set(Component.translatable("gui.simplyscreens.status.linked"));
    }

    private void confirmRemove(ImageEntry image) {
        Card dialog = Ui.card().elevated(true).outlined(true).padding(14);
        OverlayHandle[] handle = { null };
        ButtonWidget cancel = Ui.button(Component.translatable("gui.simplyscreens.cancel"),
                () -> { if (handle[0] != null) handle[0].close(); }).ghost();
        ButtonWidget remove = Ui.button(Component.translatable("gui.simplyscreens.screen.remove"), () -> {
            if (handle[0] != null) handle[0].close();
            PacketRegistries.sendToServer(new RemoveImageC2SPacket(image.imageId()));
            status.set(Component.translatable("gui.simplyscreens.status.removing", image.displayName()));
        }).danger();
        dialog.addChild(Ui.column(
                Ui.heading(Component.translatable("gui.simplyscreens.remove.confirm.title")),
                Ui.text(Component.translatable("gui.simplyscreens.remove.confirm.body", image.displayName())),
                Ui.row(cancel, remove).gap(6)
        ).gap(10));
        dialog.width(250).minHeight(90);
        handle[0] = Dialog.show(uiRuntime().overlays(), dialog);
    }

    private void onUploadFromComputer() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(3);
            filters.put(stack.UTF8("*.png"));
            filters.put(stack.UTF8("*.jpg"));
            filters.put(stack.UTF8("*.jpeg"));
            filters.flip();
            String filePath = TinyFileDialogs.tinyfd_openFileDialog(
                    Component.translatable("gui.simplyscreens.screen.dialog.select_image").getString(),
                    "", filters,
                    Component.translatable("gui.simplyscreens.screen.dialog.image_files").getString(), false);
            if (filePath == null) return;
            if (!ImageImportSupport.isSupportedFile(filePath)) {
                showError(Component.translatable("gui.simplyscreens.screen.upload.error.type"));
                return;
            }
            uploadFile(Paths.get(filePath));
        }
    }

    private void uploadFile(Path path) {
        try {
            long fileSize = Files.size(path);
            if (fileSize <= 0 || fileSize > ClientServerConfig.maxUploadSize()) {
                showError(Component.translatable("gui.simplyscreens.screen.upload.error.size",
                        ClientServerConfig.maxUploadSize() / 1024 / 1024));
                return;
            }
            byte[] data = Files.readAllBytes(path);
            if (data.length > ClientServerConfig.maxUploadSize()) {
                showError(Component.translatable("gui.simplyscreens.screen.upload.error.size",
                        ClientServerConfig.maxUploadSize() / 1024 / 1024));
                return;
            }
            UUID transactionId = UUID.randomUUID();
            int totalChunks = (int) Math.ceil((double) data.length / CHUNK_SIZE);
            for (int index = 0; index < totalChunks; index++) {
                int start = index * CHUNK_SIZE;
                int end = Math.min(data.length, start + CHUNK_SIZE);
                byte[] chunk = java.util.Arrays.copyOfRange(data, start, end);
                PacketRegistries.sendToServer(new UploadImageChunkC2SPacket(
                        blockEntityPos, transactionId, index, totalChunks, chunk,
                        index == 0 ? path.getFileName().toString() : null));
            }
            tab.set(Tab.GALLERY);
            status.set(Component.translatable("gui.simplyscreens.status.uploading"));
        } catch (java.io.IOException | SecurityException exception) {
            SimplyScreens.LOGGER.error("Failed to read image file", exception);
            showError(Component.translatable("gui.simplyscreens.screen.upload.error.read"));
        }
    }

    private void onDownloadFromUrl() {
        String url = imageUrl.get().trim();
        if (!ImageImportSupport.isHttpUrl(url)) {
            showError(Component.translatable("gui.simplyscreens.screen.url.error.invalid"));
            return;
        }
        PacketRegistries.sendToServer(new DownloadImageFromUrlC2SPacket(
                blockEntityPos, url, ImageImportSupport.fileNameFromUrl(url)));
        tab.set(Tab.GALLERY);
        status.set(Component.translatable("gui.simplyscreens.status.downloading"));
    }

    private void showError(Component message) {
        status.set(message);
        if (uiRuntime() != null) {
            Toast.show(uiRuntime().overlays(), Toast.error(
                    Component.translatable("gui.simplyscreens.error").getString(), message.getString()));
        }
    }

    private Component displayedImageName() {
        UUID displayed = displayedImageId.get();
        if (displayed == null) return Component.translatable("gui.simplyscreens.gallery.none_displayed");
        for (ImageEntry image : images.get()) {
            if (image.imageId().equals(displayed)) {
                return Component.translatable("gui.simplyscreens.gallery.now_displaying", image.displayName());
            }
        }
        return Component.translatable("gui.simplyscreens.gallery.now_displaying", displayed.toString());
    }

    private boolean hasAnchor() {
        if (Minecraft.getInstance().level == null) return false;
        BlockEntity blockEntity = Minecraft.getInstance().level.getBlockEntity(blockEntityPos);
        return blockEntity instanceof ScreenBlockEntity screen && screen.getAnchorEntity() != null;
    }

    private void fetchDataFromBlockEntity() {
        applyingRemoteState = true;
        try {
            if (Minecraft.getInstance().level == null) return;
            BlockEntity blockEntity = Minecraft.getInstance().level.getBlockEntity(blockEntityPos);
            if (blockEntity instanceof ScreenBlockEntity screen) {
                ScreenBlockEntity anchor = screen.getAnchorEntity();
                if (anchor != null) {
                    displayedImageId.set(anchor.getImageId());
                    selectedImageId.set(anchor.getImageId());
                    screenId.set(anchor.getScreenId() != null ? anchor.getScreenId() : "");
                    maintainAspectRatio.set(anchor.isMaintainAspectRatio());
                }
            }
        } finally {
            applyingRemoteState = false;
        }
    }

    public void updateImageList(List<ImageMetadata> metadata) {
        List<ImageEntry> next = new ArrayList<>();
        if (metadata != null) {
            for (ImageMetadata image : metadata) {
                try {
                    next.add(new ImageEntry(image.getName(), UUID.fromString(image.getId())));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        images.set(List.copyOf(next));
        UUID selected = selectedImageId.get();
        boolean selectionExists = selected != null && next.stream().anyMatch(image -> image.imageId().equals(selected));
        if (!selectionExists) {
            UUID displayed = displayedImageId.get();
            selectedImageId.set(next.stream().anyMatch(image -> image.imageId().equals(displayed)) ? displayed : null);
        }
        status.set(Component.empty());
    }

    public void refreshImageList() {
        PacketRegistries.sendToServer(new RequestImageListC2SPacket());
    }

    public boolean matchesScreenUpdate(BlockPos position, BlockPos anchorPosition) {
        return blockEntityPos.equals(position) || blockEntityPos.equals(anchorPosition);
    }

    public void updateScreenState(UUID imageId, boolean maintainAspectRatio, String screenId) {
        applyingRemoteState = true;
        try {
            displayedImageId.set(imageId);
            this.maintainAspectRatio.set(maintainAspectRatio);
            this.screenId.set(screenId != null ? screenId : "");
        } finally {
            applyingRemoteState = false;
        }
    }

    @Override
    public void removed() {
        aspectSubscription.close();
        galleryState.close();
        filteredImages.close();
        super.removed();
    }
}
