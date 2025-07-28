package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.client.gui.widgets.ImageListWidget;
import com.nstut.simplyscreens.client.screens.ImageLoadScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public class ClientPacketHandler {
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