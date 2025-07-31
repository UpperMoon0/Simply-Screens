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
    public static void handleUpdateScreen(BlockPos pos, UUID imageId) {
        Level level = Minecraft.getInstance().level;
        if (level != null) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                screenBlockEntity.setImageId(imageId);
            }
        }
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