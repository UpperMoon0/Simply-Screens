package com.nstut.simplyscreens.client.renderers;

import com.mojang.blaze3d.platform.CompareOp;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenColorRestoreTest {
    @Test
    void replayCopiesTheActualWorldAndPoseTransforms() {
        Matrix4f world = new Matrix4f().rotateY(0.6f);
        Matrix4f pose = new Matrix4f().translate(5, 2, -7).rotateX(0.3f);
        Matrix4f expectedWorld = new Matrix4f(world);
        Matrix4f expectedPose = new Matrix4f(pose);
        var captured = ScreenColorRestore.snapshotTransforms(world, pose);

        world.identity();
        pose.identity();

        assertTrue(expectedWorld.equals(captured.modelView(), 0.000001f));
        assertTrue(expectedPose.equals(captured.pose(), 0.000001f));
    }

    @Test
    void replayKeepsOcclusionWithoutDepthWritesOrSecondAlphaBlend() {
        var pipeline = ScreenColorRestore.PIPELINE;
        var depth = pipeline.getDepthStencilState();
        assertEquals(CompareOp.LESS_THAN_OR_EQUAL, depth.depthTest());
        assertFalse(depth.writeDepth());
        // One extra raster-pixel slope margin and16precision units beyond the world image.
        assertEquals(-2.0f, depth.depthBiasScaleFactor());
        assertEquals(-144.0f, depth.depthBiasConstant());
        assertTrue(pipeline.getColorTargetState().blendFunction().isEmpty());
    }
}
