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
    private void simplyscreens$beginFrame(CallbackInfo ci) { ScreenColorRestore.beginFrame(); }

    // Iris/Oculus finalize inside LevelRenderer. A LevelRenderer RETURN callback is order-dependent;
    // GameRenderer TAIL is too late because hand rendering clears the shared world depth attachment.
    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
            shift = At.Shift.AFTER))
    private void simplyscreens$restoreImageColors(CallbackInfo ci) { ScreenColorRestore.finishWorld(); }
}
