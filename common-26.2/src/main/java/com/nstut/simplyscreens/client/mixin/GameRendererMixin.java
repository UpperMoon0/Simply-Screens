package com.nstut.simplyscreens.client.mixin;

import com.nstut.simplyscreens.client.renderers.ScreenColorRestore;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void simplyscreens$beginFrame(CallbackInfo ci) { ScreenColorRestore.beginFrame(); com.nstut.simplyscreens.client.renderers.ScreenBlockEntityRenderer.beginRenderFrame(); }

    // Iris finalizes inside LevelRenderer. This call site is after that work, before hand depth clear,
    // and before Iris's GameRenderer TAIL output color-space conversion.
    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
            shift = At.Shift.AFTER))
    private void simplyscreens$restoreImageColors(CallbackInfo ci) { ScreenColorRestore.finishWorld(); }
}
