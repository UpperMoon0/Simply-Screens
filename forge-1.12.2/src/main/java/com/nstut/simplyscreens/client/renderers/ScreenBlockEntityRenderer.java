package com.nstut.simplyscreens.client.renderers;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.blocks.ScreenBlock;
import com.nstut.simplyscreens.ScreenTileLayout;
import com.nstut.simplyscreens.ScreenVisibility;
import com.nstut.simplyscreens.helpers.ClientImageManager;
import com.nstut.simplyscreens.compat.LegacyDynamicTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.opengl.GL11;
/** Per-tile quads retain coverage when an anchor chunk is unloaded. */
public final class ScreenBlockEntityRenderer extends TileEntitySpecialRenderer<ScreenBlockEntity> {
 // LWJGL 2 vector getters require 16 slots even for a four-component query.
 static java.nio.FloatBuffer colorQueryBuffer() { return org.lwjgl.BufferUtils.createFloatBuffer(16); }
 @Override public boolean isGlobalRenderer(ScreenBlockEntity tile) { return true; }
 @Override public void render(ScreenBlockEntity tile,double x,double y,double z,float partial,int stage,float alpha) {
  BlockPos origin=tile.getAnchorPos();if(origin==null||tile.getWorld()==null)return;
  ScreenBlockEntity anchor=tile.isAnchor()?tile:tile.getAnchorEntity();ScreenBlockEntity data=anchor==null?tile:anchor;
  java.util.UUID id=data.getResolvedImageId();if(id==null)return;
  EnumFacing facing=tile.getBlockState().getValue(ScreenBlock.FACING);
  EnumFacing across=widthDirection(facing),up=facing.getAxis().isHorizontal()?EnumFacing.UP:facing==EnumFacing.UP?EnumFacing.SOUTH:EnumFacing.NORTH;
  BlockPos far=origin.offset(across,data.getScreenWidth()-1).offset(up,data.getScreenHeight()-1);
  net.minecraft.entity.Entity camera=Minecraft.getMinecraft().getRenderViewEntity();
  if(camera==null||!ScreenVisibility.isWithinDistance(camera.posX,camera.posY,camera.posZ,origin.getX(),origin.getY(),origin.getZ(),far.getX(),far.getY(),far.getZ(),com.nstut.simplyscreens.client.ui.SimplyScreensUiPreferences.getViewDistance()))return;
  ResourceLocation location=ClientImageManager.getTextureLocation(id);LegacyDynamicTexture texture=ClientImageManager.getImageTexture(id);if(location==null||texture==null||texture.getPixels()==null)return;
  float width=data.getScreenWidth(),height=data.getScreenHeight();
  if(data.isMaintainAspectRatio()) { float aspect=(float)texture.getPixels().getWidth()/texture.getPixels().getHeight();if(aspect>width/height)height=width/aspect;else width=height*aspect; }
  BlockPos pos=tile.getPos();int dx=pos.getX()-origin.getX(),dy=pos.getY()-origin.getY(),dz=pos.getZ()-origin.getZ();
  ScreenTileLayout.Tile quad=ScreenTileLayout.calculate(data.getScreenWidth(),data.getScreenHeight(),dx*across.getXOffset()+dy*across.getYOffset()+dz*across.getZOffset(),dx*up.getXOffset()+dy*up.getYOffset()+dz*up.getZOffset(),width,height);if(quad.isEmpty())return;
  float lightX=OpenGlHelper.lastBrightnessX,lightY=OpenGlHelper.lastBrightnessY;
  boolean lighting=GL11.glIsEnabled(GL11.GL_LIGHTING),culling=GL11.glIsEnabled(GL11.GL_CULL_FACE),blending=GL11.glIsEnabled(GL11.GL_BLEND);
  int src=GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_SRC_RGB),dst=GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_DST_RGB),srcAlpha=GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_SRC_ALPHA),dstAlpha=GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_DST_ALPHA);
  java.nio.FloatBuffer color=colorQueryBuffer();GL11.glGetFloat(GL11.GL_CURRENT_COLOR,color);
  GlStateManager.pushMatrix();
  try {
   GlStateManager.translate(x-dx+.5,y-dy+.5,z-dz+.5);rotate(facing);GlStateManager.translate(-(data.getScreenWidth()-1)/2f,(data.getScreenHeight()-1)/2f,(facing==EnumFacing.NORTH||facing==EnumFacing.SOUTH)?-.501f:.501f);
   bindTexture(location);GlStateManager.disableLighting();GlStateManager.disableCull();GlStateManager.enableBlend();GlStateManager.blendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA);GlStateManager.color(1,1,1,1);OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit,240,240);
   BufferBuilder b=Tessellator.getInstance().getBuffer();b.begin(GL11.GL_QUADS,DefaultVertexFormats.POSITION_TEX_COLOR);
   b.pos(quad.minX(),quad.maxY(),0).tex(quad.maxU(),quad.minV()).color(255,255,255,255).endVertex();
   b.pos(quad.maxX(),quad.maxY(),0).tex(quad.minU(),quad.minV()).color(255,255,255,255).endVertex();
   b.pos(quad.maxX(),quad.minY(),0).tex(quad.minU(),quad.maxV()).color(255,255,255,255).endVertex();
   b.pos(quad.minX(),quad.minY(),0).tex(quad.maxU(),quad.maxV()).color(255,255,255,255).endVertex();Tessellator.getInstance().draw();
  } finally { OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit,lightX,lightY);if(lighting)GlStateManager.enableLighting();else GlStateManager.disableLighting();if(culling)GlStateManager.enableCull();else GlStateManager.disableCull();if(blending)GlStateManager.enableBlend();else GlStateManager.disableBlend();GlStateManager.tryBlendFuncSeparate(src,dst,srcAlpha,dstAlpha);GlStateManager.color(color.get(0),color.get(1),color.get(2),color.get(3));GlStateManager.popMatrix(); }
 }
 private static EnumFacing widthDirection(EnumFacing facing) { switch(facing) { case SOUTH:return EnumFacing.EAST;case WEST:return EnumFacing.SOUTH;case EAST:return EnumFacing.NORTH;default:return EnumFacing.WEST; } }
 private static void rotate(EnumFacing facing) { switch(facing) { case SOUTH:GlStateManager.rotate(180,0,1,0);break;case WEST:GlStateManager.rotate(270,0,1,0);GlStateManager.scale(-1,1,1);break;case EAST:GlStateManager.rotate(90,0,1,0);GlStateManager.scale(-1,1,1);break;case UP:GlStateManager.rotate(270,1,0,0);GlStateManager.scale(1,-1,1);break;case DOWN:GlStateManager.rotate(90,1,0,0);GlStateManager.scale(1,-1,1);break;default:break; } }
}
