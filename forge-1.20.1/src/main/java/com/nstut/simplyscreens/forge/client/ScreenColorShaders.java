package com.nstut.simplyscreens.forge.client;

import com.nstut.simplyscreens.client.renderers.ScreenColorRestore;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;

@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = "simply_screens", bus = net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus.MOD, value = net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class ScreenColorShaders {
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void register(net.minecraftforge.client.event.RegisterShadersEvent event) throws java.io.IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
                new net.minecraft.resources.ResourceLocation("simply_screens", "color_restore"), DefaultVertexFormat.POSITION_TEX), ScreenColorRestore::setShader);
    }
}
