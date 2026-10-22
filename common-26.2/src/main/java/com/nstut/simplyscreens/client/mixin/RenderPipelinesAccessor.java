package com.nstut.simplyscreens.client.mixin;
import net.minecraft.client.renderer.RenderPipelines;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(RenderPipelines.class)
public interface RenderPipelinesAccessor {
    @Invoker("register") static RenderPipeline simplyscreens$register(RenderPipeline pipeline) { throw new AssertionError("Mixin not applied"); }
}
