package com.nstut.simplyscreens.client.renderers;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;
import com.mojang.math.Vector4f;
import com.nstut.simplyscreens.client.compat.ShaderPackState;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL13;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/** Native 1.16 compatibility-profile replay after Iris finalization and before hand depth clear. */
public final class ScreenColorRestore {
    private static final List<Quad> FRAME = new ArrayList<>();
    private ScreenColorRestore() {}
    public static void beginFrame() { FRAME.clear(); }
    public static void capture(ResourceLocation texture, PoseStack.Pose pose,
            float minX,float minY,float maxX,float maxY,float minU,float minV,float maxU,float maxV) {
        if (!ShaderPackState.shouldCapture()) return;
        FloatBuffer projection=BufferUtils.createFloatBuffer(16), modelView=BufferUtils.createFloatBuffer(16);
        GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX,projection);
        GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX,modelView);
        FRAME.add(new Quad(texture,pose.pose().copy(),projection,modelView,minX,minY,maxX,maxY,minU,minV,maxU,maxV));
    }
    public static void finishWorld() {
        if (FRAME.isEmpty()) return;
        int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int activeTexture=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int matrixMode=GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        // Only direct GL calls are made inside the bracket, so Blaze3D's state cache stays valid.
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPushMatrix();
        try {
            if (!ShaderPackState.shouldCapture()) return;
            GL20.glUseProgram(0);
            GL13.glActiveTexture(GL13.GL_TEXTURE1); GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glEnable(GL11.GL_TEXTURE_2D); GL11.glDisable(GL11.GL_LIGHTING); GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_BLEND); GL11.glEnable(GL11.GL_ALPHA_TEST); GL11.glAlphaFunc(GL11.GL_GREATER,0.99999F);
            GL11.glEnable(GL11.GL_DEPTH_TEST); GL11.glDepthFunc(GL11.GL_LEQUAL); GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL); GL11.glPolygonOffset(-2F,-32F); GL11.glColor4f(1,1,1,1);
            for (Quad q:FRAME) {
                GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glLoadMatrixf(q.projection);
                GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glLoadMatrixf(q.modelView);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D,Minecraft.getInstance().getTextureManager().getTexture(q.texture).getId());
                GL11.glBegin(GL11.GL_QUADS);
                vertex(q.pose,q.minX,q.maxY,q.maxU,q.minV); vertex(q.pose,q.maxX,q.maxY,q.minU,q.minV);
                vertex(q.pose,q.maxX,q.minY,q.minU,q.maxV); vertex(q.pose,q.minX,q.minY,q.maxU,q.maxV);
                GL11.glEnd();
            }
        } finally {
            GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPopMatrix();
            GL11.glMatrixMode(matrixMode); GL20.glUseProgram(program); GL11.glPopAttrib(); GL13.glActiveTexture(activeTexture); FRAME.clear();
        }
    }
    private static void vertex(Matrix4f pose,float x,float y,float u,float v) {
        Vector4f point=new Vector4f(x,y,0,1); point.transform(pose);
        GL11.glTexCoord2f(u,v); GL11.glVertex3f(point.x(),point.y(),point.z());
    }
    private static final class Quad {
        final ResourceLocation texture; final Matrix4f pose; final FloatBuffer projection,modelView;
        final float minX,minY,maxX,maxY,minU,minV,maxU,maxV;
        Quad(ResourceLocation texture,Matrix4f pose,FloatBuffer projection,FloatBuffer modelView,float minX,float minY,float maxX,float maxY,float minU,float minV,float maxU,float maxV) {
            this.texture=texture;this.pose=pose;this.projection=projection;this.modelView=modelView;
            this.minX=minX;this.minY=minY;this.maxX=maxX;this.maxY=maxY;this.minU=minU;this.minV=minV;this.maxU=maxU;this.maxV=maxV;
        }
    }
}
