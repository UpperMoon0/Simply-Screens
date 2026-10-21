package com.nstut.simplyscreens.client.testing;

import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ScreenShotHelper;

/** Exact opt-in placed-screen model check and evidence capture, not a pixel oracle. */
public final class NativePlacedModelProbe {
    private NativePlacedModelProbe() { }
    public static void verifyAndCapture(Minecraft client, ScreenBlockEntity screen, UUID session) {
        if (!Boolean.getBoolean("simplyscreens.mediaSmokeTest")) return;
        IBlockState state=client.world.getBlockState(screen.getPos());
        IBakedModel model=client.getBlockRendererDispatcher().getModelForState(state);
        IBakedModel missing=client.getBlockRendererDispatcher().getBlockModelShapes().getModelManager().getMissingModel();
        TextureAtlasSprite missingSprite=client.getTextureMapBlocks().getMissingSprite();
        List<BakedQuad> quads=new ArrayList<>(model.getQuads(state,null,0));
        for(EnumFacing side:EnumFacing.values())quads.addAll(model.getQuads(state,side,0));
        if(model==missing || quads.isEmpty())fail("placed block resolves to missing/empty native model");
        for(BakedQuad quad:quads) {
            TextureAtlasSprite sprite=quad.getSprite();
            if(sprite==null || sprite==missingSprite || missingSprite.getIconName().equals(sprite.getIconName()))
                fail("placed block resolves to missing native sprite");
        }
        String name="simplyscreens-legacy-media-"+session+".png";
        File capture=new File(new File(client.gameDir,"screenshots"),name);
        if(capture.exists())fail("screenshot evidence already exists");
        ScreenShotHelper.saveScreenshot(client.gameDir,name,client.displayWidth,client.displayHeight,client.getFramebuffer());
        try {
            java.awt.image.BufferedImage image=ImageIO.read(capture);
            if(image==null || image.getWidth()!=client.displayWidth || image.getHeight()!=client.displayHeight)
                fail("native screenshot missing or invalid");
        } catch(java.io.IOException error) { throw new IllegalStateException("SIMPLYSCREENS_LEGACY_MEDIA_FAIL screenshot capture",error); }
        System.out.println("SIMPLYSCREENS_LEGACY_PLACED_MODEL_PASS modelNonMissing=true spritesNonMissing=true screenshot="+name);
    }
    private static void fail(String message) { throw new IllegalStateException("SIMPLYSCREENS_LEGACY_MEDIA_FAIL "+message); }
}
