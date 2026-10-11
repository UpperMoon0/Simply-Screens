package com.nstut.simplyscreens.neoforge.client;

import com.nstut.simplyscreens.client.renderers.ScreenColorRestore;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;

@net.neoforged.fml.common.EventBusSubscriber(modid = "simply_screens", bus = net.neoforged.fml.common.EventBusSubscriber.Bus.MOD, value = net.neoforged.api.distmarker.Dist.CLIENT)
public final class ScreenColorShaders {
    @net.neoforged.bus.api.SubscribeEvent
    public static void register(net.neoforged.neoforge.client.event.RegisterShadersEvent event) throws java.io.IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("simply_screens", "color_restore"), DefaultVertexFormat.POSITION_TEX), ScreenColorRestore::setShader);
    }
}
