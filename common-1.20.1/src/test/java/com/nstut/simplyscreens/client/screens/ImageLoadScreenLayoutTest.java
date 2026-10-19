package com.nstut.simplyscreens.client.screens;

import com.nstut.openui.api.UIComponent;
import com.nstut.openui.controls.ScrollView;
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.FormattedText;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageLoadScreenLayoutTest {
    @TempDir
    static Path tempDir;

    @BeforeAll
    static void isolatePreferences() {
        System.setProperty("simplyscreens.ui.config.dir", tempDir.toString());
    }

    @AfterAll
    static void restorePreferences() {
        System.clearProperty("simplyscreens.ui.config.dir");
    }

    @Test
    void normalViewportLaysOutInsideBounds() {
        ImageLoadScreen screen = new ImageLoadScreen(BlockPos.ZERO); screen.width=320; screen.height=240;
        UIComponent root = screen.buildUI();
        root.layoutTree(stubFont(), 0, 0, 320, 240);
        assertFalse(containsScroll(root), "A normal viewport must use the fixed panel, not the scrollable shell");
        assertTrue(maxBottom(root, 0) <= 240,
                "Every laid out component must stay inside the viewport at 320x240");
    }

    @Test
    void tinyViewportSwitchesToScrollableShell() {
        UIComponent root = new ImageLoadScreen(BlockPos.ZERO).buildUI();
        root.layoutTree(stubFont(), 0, 0, 320, 100);
        assertTrue(containsScroll(root),
                "Below the fixed-chrome height budget the panel must wrap in a scrollable shell");
    }

    @Test void populatedPlaylistKeepsThePlayerAndActionTargetsInsideCompactAndWidePanels() throws Exception {
        for (int[] size : new int[][]{{320,240},{360,280},{480,270},{640,360},{960,540}}) {
            ImageLoadScreen screen=new ImageLoadScreen(BlockPos.ZERO); screen.width=size[0];screen.height=size[1];
            java.util.UUID list=java.util.UUID.randomUUID(), image=java.util.UUID.randomUUID();
            var pending=ImageLoadScreen.class.getDeclaredField("pendingPlaylistRequest");pending.setAccessible(true);pending.set(screen,1L);
            var slides=java.util.stream.IntStream.range(0,12).mapToObj(i ->
                    new com.nstut.simplyscreens.playlist.PlaylistWire.Slide(image,10000)).toList();
            screen.updateImageList(List.of(new com.nstut.simplyscreens.helpers.ImageMetadata("Long image filename ".repeat(8),image.toString(),"png")));
            screen.updatePlaylist(BlockPos.ZERO,new com.nstut.simplyscreens.playlist.PlaylistWire.View(
                    "network/test",0,1,1,1,true,true,list,
                    List.of(new com.nstut.simplyscreens.playlist.PlaylistWire.Summary(list,"Long playlist name ".repeat(3).trim(),12)),
                    slides,"PLAYING",image,0,1000,"LIST",true,List.of(),List.of()).json());
            screen.smokeSelectTab(1);
            UIComponent root=screen.buildUI(); root.layoutTree(stubFont(),0,0,size[0],size[1]);
            for (String key : List.of("playlist.toolbar","playlist.transport","playlist.now_playing","playlist.modes")) {
                UIComponent component=find(root,key);assertTrue(component!=null,key);
                assertTrue(component.getHeight()>0 && component.getY()>=0 && component.getY()+component.getHeight()<=size[1],key+" outside viewport");
                assertTargetsInside(component,component.getX(),component.getX()+component.getWidth());
            }
            UIComponent rows=findList(root);assertTrue(rows!=null,"Missing slides");
            assertTrue(rows.children().stream().anyMatch(row -> row.getY()>=rows.getY() &&
                    row.getY()+row.getHeight()<=rows.getY()+rows.getHeight()),"No complete slide at "+size[0]+"x"+size[1]);
        }
    }
    private static UIComponent find(UIComponent node,String key) {
        if (key.equals(node.key())) return node;
        for (UIComponent child:node.children()) {UIComponent found=find(child,key);if(found!=null)return found;}
        return null;
    }
    private static UIComponent findList(UIComponent node) {
        if(node instanceof com.nstut.openui.controls.VirtualList<?>)return node;
        for(UIComponent child:node.children()){UIComponent found=findList(child);if(found!=null)return found;}
        return null;
    }
    private static void assertTargetsInside(UIComponent node,int left,int right) {
        if(node instanceof com.nstut.openui.api.ButtonWidget)
            assertTrue(node.getWidth()>0 && node.getX()>=left && node.getX()+node.getWidth()<=right,"Action target outside panel: "+node.key());
        for(UIComponent child:node.children())assertTargetsInside(child,left,right);
    }

    private static boolean containsScroll(UIComponent component) {
        if (component instanceof ScrollView) return true;
        for (UIComponent child : component.children()) {
            if (containsScroll(child)) return true;
        }
        return false;
    }

    private static int maxBottom(UIComponent component, int best) {
        int current = best;
        if (component.getHeight() > 0) {
            current = Math.max(current, component.getY() + component.getHeight());
        }
        for (UIComponent child : component.children()) {
            current = maxBottom(child, current);
        }
        return current;
    }

    /** Measures without a real FontManager, mirroring OpenUI's own Toast tests. */
    private static Font stubFont() {
        return new Font(null, false) {
            @Override public int width(String text) { return text.length() * 6; }
            @Override public int width(FormattedText text) { return text.getString().length() * 6; }
            @Override public int width(FormattedCharSequence text) { return 40; }
            @Override public List<FormattedCharSequence> split(FormattedText text, int maxWidth) {
                int charsPerLine = Math.max(1, maxWidth / 6);
                String value = text.getString();
                List<FormattedCharSequence> lines = new ArrayList<>();
                for (int i = 0; i < value.length(); i += charsPerLine) {
                    lines.add(Component.literal(value.substring(i, Math.min(value.length(), i + charsPerLine))).getVisualOrderText());
                }
                if (lines.isEmpty()) lines.add(Component.literal("").getVisualOrderText());
                return lines;
            }
        };
    }
}
