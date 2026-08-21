package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.client.gui.widgets.ImageListWidget;
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
        screen.setScreenId(screenId);
    }

    public static void handleInvalidateImage(UUID imageId) {
    }

    public static void handleConfigSync(boolean disableUpload, boolean disableUrlDownload, int maxUploadSize) {
        Config.DISABLE_UPLOAD = disableUpload;
        Config.DISABLE_URL_DOWNLOAD = disableUrlDownload;
        Config.MAX_UPLOAD_SIZE = maxUploadSize;
    }

    public static void handleUploadComplete() {
        Minecraft mc = Minecraft.getInstance();
        Screen currentScreen = mc.screen;

        if (currentScreen instanceof ImageLoadScreen) {
            ImageListWidget imageListWidget = ((ImageLoadScreen) currentScreen).getImageListWidget();
            if (imageListWidget != null) {
                imageListWidget.refresh();
            }
        }
    }
}
