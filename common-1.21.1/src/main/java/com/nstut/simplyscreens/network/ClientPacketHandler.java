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
    public static void handleUpdateScreen(BlockPos pos, BlockPos anchorPos, UUID imageId, boolean maintainAspectRatio, String screenId, int screenWidth, int screenHeight) {
        Level level = Minecraft.getInstance().level;
        if (level != null) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                applyScreenUpdate(screenBlockEntity, anchorPos, imageId, maintainAspectRatio,
                        screenId, screenWidth, screenHeight);
            }
            if (Minecraft.getInstance().screen instanceof ImageLoadScreen imageLoadScreen
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

    /** Keeps an open configuration screen reactive after vanilla block-entity sync. */
    public static void handleBlockEntityUpdate(ScreenBlockEntity screen) {
        notifyOpenScreen(Minecraft.getInstance().screen, screen);
    }

    static void notifyOpenScreen(Screen currentScreen, ScreenBlockEntity screen) {
        BlockPos anchorPos = screen.getAnchorPos();
        if (anchorPos != null && currentScreen instanceof ImageLoadScreen imageLoadScreen
                && imageLoadScreen.matchesScreenUpdate(screen.getBlockPos(), anchorPos)) {
            imageLoadScreen.updateScreenState(
                    screen.getResolvedImageId(), screen.isMaintainAspectRatio(), screen.getScreenId());
        }
    }

    public static void handleInvalidateImage(UUID imageId) {
        ClientImageManager.invalidateImage(imageId);
    }

    public static void handleConfigSync(boolean disableUpload, boolean disableUrlDownload, int maxUploadSize) {
        ClientServerConfig.apply(disableUpload, disableUrlDownload, maxUploadSize);
    }

    public static void handleUploadComplete() {
        Minecraft mc = Minecraft.getInstance();
        Screen currentScreen = mc.screen;

        if (currentScreen instanceof ImageLoadScreen imageLoadScreen) {
            imageLoadScreen.refreshImageList();
        }
    }
}
