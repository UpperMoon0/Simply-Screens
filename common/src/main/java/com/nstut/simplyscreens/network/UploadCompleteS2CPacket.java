package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.client.gui.widgets.ImageListWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import com.nstut.simplyscreens.client.screens.ImageLoadScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class UploadCompleteS2CPacket {
    public UploadCompleteS2CPacket() {
    }

    public UploadCompleteS2CPacket(FriendlyByteBuf buf) {
    }

    public void encode(FriendlyByteBuf buf) {
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                Minecraft mc = Minecraft.getInstance();
                Screen currentScreen = mc.screen;

                if (currentScreen instanceof ImageLoadScreen) {
                    ImageListWidget imageListWidget = ((ImageLoadScreen) currentScreen).getImageListWidget();
                    if (imageListWidget != null) {
                        imageListWidget.refresh();
                    }
                }
            });
        });
        context.setPacketHandled(true);
    }
}