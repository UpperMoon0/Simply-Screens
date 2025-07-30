package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.DisplayMode;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.helpers.ServerImageCache;
import dev.architectury.networking.NetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Supplier;

public class RequestImageUploadC2SPacket {
    public static final ResourceLocation ID = new ResourceLocation(SimplyScreens.MOD_ID, "request_image_upload");

    private final String imageName;
    private final String imageHash;
    private final String imageExtension;
    private final BlockPos blockPos;
    private final boolean maintainAspectRatio;
    private final DisplayMode displayMode;
    private final String url;

    public RequestImageUploadC2SPacket(String imageName, String imageHash, String imageExtension, BlockPos blockPos, boolean maintainAspectRatio, DisplayMode displayMode, String url) {
        this.imageName = imageName;
        this.imageHash = imageHash;
        this.imageExtension = imageExtension;
        this.blockPos = blockPos;
        this.maintainAspectRatio = maintainAspectRatio;
        this.displayMode = displayMode;
        this.url = url;
    }

    public RequestImageUploadC2SPacket(String imageName, String imageHash, String imageExtension, BlockPos blockPos, boolean maintainAspectRatio) {
        this(imageName, imageHash, imageExtension, blockPos, maintainAspectRatio, DisplayMode.LOCAL, null);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(imageName);
        buf.writeUtf(imageHash);
        buf.writeUtf(imageExtension);
        buf.writeBlockPos(blockPos);
        buf.writeBoolean(maintainAspectRatio);
        buf.writeEnum(displayMode);
        buf.writeBoolean(url != null);
        if (url != null) {
            buf.writeUtf(url);
        }
    }

    public static RequestImageUploadC2SPacket read(FriendlyByteBuf buf) {
        String imageName = buf.readUtf();
        String imageHash = buf.readUtf();
        String imageExtension = buf.readUtf();
        BlockPos blockPos = buf.readBlockPos();
        boolean maintainAspectRatio = buf.readBoolean();
        DisplayMode displayMode = buf.readEnum(DisplayMode.class);
        String url = buf.readBoolean() ? buf.readUtf() : null;
        return new RequestImageUploadC2SPacket(imageName, imageHash, imageExtension, blockPos, maintainAspectRatio, displayMode, url);
    }

    public static void apply(RequestImageUploadC2SPacket msg, Supplier<NetworkManager.PacketContext> context) {
        ServerPlayer player = (ServerPlayer) context.get().getPlayer();
        context.get().queue(() -> ServerImageCache.handleRequestImageUpload(msg, player));
    }

    public String getImageName() {
        return imageName;
    }

    public String getImageHash() {
        return imageHash;
    }

    public String getImageExtension() {
        return imageExtension;
    }

    public BlockPos getBlockPos() {
        return blockPos;
    }

    public boolean isMaintainAspectRatio() {
        return maintainAspectRatio;
    }

    public DisplayMode getDisplayMode() {
        return displayMode;
    }

    public String getUrl() {
        return url;
    }
}