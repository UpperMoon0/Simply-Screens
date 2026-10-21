package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.helpers.ClientImageManager;
import com.nstut.simplyscreens.client.ClientServerConfig;
import com.nstut.simplyscreens.client.screens.ImageLoadScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.UUID;

public class ClientPacketHandler {
    public static void handleScreenPrefetch(BlockPos pos, String json) {
        var client = Minecraft.getInstance();
        if (client.level == null || !(client.level.getBlockEntity(pos) instanceof ScreenBlockEntity)) return;
        try {
            String[] ids = json.isEmpty() ? new String[0] : json.split(",", -1);
            if (ids.length > 2) return;
            for (String id : ids) ClientImageManager.prefetch(UUID.fromString(id));
        } catch (IllegalArgumentException malformed) { /* Bounded server hint, never an arbitrary URL. */ }
    }

    public static void handleScreenPlaylist(BlockPos pos, String json) {
        if (Minecraft.getInstance().gui.screen() instanceof ImageLoadScreen screen) screen.updatePlaylist(pos, json);
    }

    public static void handleScreenAccess(BlockPos pos, String json) {
        if (Minecraft.getInstance().gui.screen() instanceof ImageLoadScreen screen) screen.updateAccess(pos, json);
    }

    public static void handleUpdateScreen(BlockPos pos, BlockPos anchorPos, UUID imageId, boolean maintainAspectRatio, String screenId, int screenWidth, int screenHeight) {
        Level level = Minecraft.getInstance().level;
        if (level != null) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                applyScreenUpdate(screenBlockEntity, anchorPos, imageId, maintainAspectRatio,
                        screenId, screenWidth, screenHeight);
            }
            if (Minecraft.getInstance().gui.screen() instanceof ImageLoadScreen imageLoadScreen
                    && imageLoadScreen.matchesScreenUpdate(pos, anchorPos)) {
                imageLoadScreen.updateScreenState(imageId, maintainAspectRatio, screenId);
            }
        }
    }

    static void applyScreenUpdate(ScreenBlockEntity screen, BlockPos anchorPos, UUID imageId,
                                  boolean maintainAspectRatio, String screenId,
                                  int screenWidth, int screenHeight) {
        screen.setAnchorPos(anchorPos);
        screen.setImageId(imageId);
        screen.setMaintainAspectRatio(maintainAspectRatio);
        screen.setScreenWidth(screenWidth);
        screen.setScreenHeight(screenHeight);
        if (screenId != null) screen.setScreenId(screenId);
    }

    public static void handleInvalidateImage(UUID imageId) {
        ClientImageManager.invalidateImage(imageId);
    }

    public static void handleConfigSync(boolean disableUpload, boolean disableUrlDownload, int maxUploadSize) {
        ClientServerConfig.apply(disableUpload, disableUrlDownload, maxUploadSize);
    }

    public static void handleUploadComplete() {
        Minecraft mc = Minecraft.getInstance();
        Screen currentScreen = mc.gui.screen();

        if (currentScreen instanceof ImageLoadScreen imageLoadScreen) {
            imageLoadScreen.refreshImageList();
        }
    }
}
