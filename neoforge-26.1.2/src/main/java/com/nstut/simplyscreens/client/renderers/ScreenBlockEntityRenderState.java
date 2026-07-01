package com.nstut.simplyscreens.client.renderers;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

public final class ScreenBlockEntityRenderState extends BlockEntityRenderState {
    public boolean visible;
    public Direction facing = Direction.NORTH;
    public Identifier texture;
    public float scaleX = 1;
    public float scaleY = 1;
    public int width = 1;
    public int height = 1;
    public int anchorOffsetX;
    public int anchorOffsetY;
    public int anchorOffsetZ;
}
