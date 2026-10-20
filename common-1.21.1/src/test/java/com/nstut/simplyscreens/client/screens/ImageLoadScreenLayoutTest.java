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

import static org.junit.jupiter.api.Assertions.*;


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
        for (String playbackStatus : new String[]{"PLAYING","ANNOUNCEMENT"})
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
                    slides,playbackStatus,image,0,1000,"LIST",true,List.of(),List.of()).json());
            screen.updateScreenState(image,true,"");
            screen.smokeSelectTab(1);
            UIComponent root=screen.buildUI(); root.layoutTree(stubFont(),0,0,size[0],size[1]);
            for (String key : List.of("playlist.toolbar","playlist.transport")) {
                UIComponent component=find(root,key);assertTrue(component!=null,key);
                assertTrue(component.getHeight()>0 && component.getY()>=0 && component.getY()+component.getHeight()<=size[1],key+" outside viewport");
                assertTargetsInside(component,component.getX(),component.getX()+component.getWidth());
            }
            var start=(com.nstut.openui.api.ButtonWidget)find(root,"playlist.start");
            assertTrue(start.getWidth() >= stubFont().width(start.getLabel()) + 16,
                    "Start must reserve its full label and truncation padding");
            UIComponent header=find(root,"screen.header"),elapsed=find(root,"playlist.elapsed");
            assertNotNull(elapsed,"Elapsed time must be shown with the displayed image");
            assertSame(elapsed,find(header,"playlist.elapsed"));
            assertNull(find(find(root,"playlist.transport"),"playlist.elapsed"));
            assertEquals(24,find(root,"playlist.transport").getHeight(),"The footer must contain one button row only");
            assertNull(find(root,"playlist.now_playing"));assertNull(find(root,"playlist.modes"));
            UIComponent controls=find(root,"playlist.controls");assertNotNull(controls);
            UIComponent first=controls.children().get(0),last=controls.children().get(controls.children().size()-1);
            assertEquals(controls.getX()+controls.getWidth()/2.0,
                    (first.getX()+last.getX()+last.getWidth())/2.0,1.0,"Player controls must be centered");
            assertEquals("ANNOUNCEMENT".equals(playbackStatus)?8:7,controls.children().size(),"End announcement must only appear during an announcement");
            UIComponent rows=findList(root);assertTrue(rows!=null,"Missing slides");
            assertTrue(rows.children().stream().filter(row -> row.getY()>=rows.getY() &&
                    row.getY()+row.getHeight()<=rows.getY()+rows.getHeight()).count()>=2,"Fewer than two complete slides at "+size[0]+"x"+size[1]);
        }
    }

    @Test void draggingPlaylistAndQueueRowsSendsOneAuthoritativeMove() throws Exception {
        for (boolean reverse : new boolean[]{false,true})
        for (boolean queue : new boolean[]{false,true}) {
            ImageLoadScreen screen=new ImageLoadScreen(BlockPos.ZERO);
            java.util.UUID list=java.util.UUID.randomUUID(),image=java.util.UUID.randomUUID();
            var pending=ImageLoadScreen.class.getDeclaredField("pendingPlaylistRequest");pending.setAccessible(true);pending.set(screen,1L);
            var slides=java.util.stream.IntStream.range(0,3).mapToObj(i ->
                    new com.nstut.simplyscreens.playlist.PlaylistWire.Slide(image,10000)).toList();
            screen.updatePlaylist(BlockPos.ZERO,new com.nstut.simplyscreens.playlist.PlaylistWire.View(
                    "network/test",7,4,3,1,true,true,list,
                    List.of(new com.nstut.simplyscreens.playlist.PlaylistWire.Summary(list,"Slides",3)),slides,
                    "PLAYING",image,0,1000,"LIST",false,slides,List.of()).json());
            var rowsField=ImageLoadScreen.class.getDeclaredField(queue?"queuedSlides":"savedSlides");rowsField.setAccessible(true);
            var rows=(List<?>)((com.nstut.openui.state.Signal<?>)rowsField.get(screen)).get();
            var builder=ImageLoadScreen.class.getDeclaredMethod("buildSlideRow",rows.get(0).getClass(),boolean.class);builder.setAccessible(true);
            int from=reverse?2:0,to=reverse?0:2;
            UIComponent first=(UIComponent)builder.invoke(screen,rows.get(from),queue);
            UIComponent last=(UIComponent)builder.invoke(screen,rows.get(to),queue);
            var source=(com.nstut.openui.dnd.Draggable<?>)first.children().get(0).children().get(0);
            var target=(com.nstut.openui.dnd.DragTarget<?>)last;
            assertFalse(((com.nstut.openui.dnd.DragTarget<?>)first).accepts(source.getData()),"Same-row drops must be ignored");
            UIComponent other=(UIComponent)builder.invoke(screen,rows.get(to),!queue);
            assertFalse(((com.nstut.openui.dnd.DragTarget<?>)other).accepts(source.getData()),"Cannot drag between the saved list and queue");
            var viewField=ImageLoadScreen.class.getDeclaredField("playlistView");viewField.setAccessible(true);
            @SuppressWarnings("unchecked") var signal=(com.nstut.openui.state.Signal<com.nstut.simplyscreens.playlist.PlaylistWire.View>)viewField.get(screen);
            var original=signal.get();
            signal.set(withAccessAndRevisions(original,false,4,3));
            assertFalse(target.accepts(source.getData()),"Read-only viewers cannot reorder");
            signal.set(withAccessAndRevisions(original,true,5,4));
            assertFalse(target.accepts(source.getData()),"A drag from an older revision cannot reorder");
            signal.set(original);assertTrue(target.accepts(source.getData()));
            UIComponent root=com.nstut.openui.api.Ui.column(first.height(28),last.height(28)).gap(2);
            var runtime=new com.nstut.openui.runtime.UiRuntime(stubFont(),new com.nstut.openui.runtime.NativeWidgetHost(){
                public void add(net.minecraft.client.gui.components.AbstractWidget widget) { }
                public void remove(net.minecraft.client.gui.components.AbstractWidget widget) { }
            });
            runtime.setRoot(root);runtime.setViewport(0,0,300,60);root.layoutTree(stubFont(),0,0,300,60);
            try(var packets=org.mockito.Mockito.mockStatic(com.nstut.simplyscreens.network.PacketRegistries.class)) {
                runtime.mouseClicked(first.getX()+40,first.getY()+10,0);
                runtime.mouseDragged(310,80,0,270,70);runtime.mouseReleased(310,80,0);
                packets.verifyNoInteractions();
                runtime.mouseClicked(first.getX()+40,first.getY()+10,0);
                runtime.mouseDragged(last.getX()+40,last.getY()+10,0,0,30);
                runtime.mouseReleased(last.getX()+40,last.getY()+10,0);
                var capture=org.mockito.ArgumentCaptor.forClass(com.nstut.simplyscreens.network.ScreenPlaylistC2SPacket.class);
                packets.verify(()->com.nstut.simplyscreens.network.PacketRegistries.sendToServer(capture.capture()));
                packets.verifyNoMoreInteractions();
                var move=com.nstut.simplyscreens.playlist.PlaylistWire.Request.parse(capture.getValue().json);
                assertEquals(queue?"MOVE_QUEUE":"MOVE",move.action());assertEquals(from,move.index());assertEquals(to,move.destination());
                assertEquals(4,move.libraryRevision());assertEquals(3,move.playbackRevision());
            }
            runtime.close();
        }
    }

    private static com.nstut.simplyscreens.playlist.PlaylistWire.View withAccessAndRevisions(
            com.nstut.simplyscreens.playlist.PlaylistWire.View v,boolean control,long library,long playback) {
        return new com.nstut.simplyscreens.playlist.PlaylistWire.View(v.key(),v.networkRevision(),library,playback,
                v.requestId(),v.success(),control,v.selected(),v.playlists(),v.slides(),v.status(),v.currentImage(),v.index(),
                v.offsetMillis(),v.repeat(),v.shuffle(),v.queue(),v.upcoming());
    }


    @Test void aServerRefreshDuringADragReleasesTheUiAndAllowsAnotherMove() throws Exception {
        var screen=new ImageLoadScreen(BlockPos.ZERO);screen.width=640;screen.height=360;
        java.util.UUID list=java.util.UUID.randomUUID(),image=java.util.UUID.randomUUID();
        var pending=ImageLoadScreen.class.getDeclaredField("pendingPlaylistRequest");pending.setAccessible(true);pending.set(screen,1L);
        var slides=List.of(new com.nstut.simplyscreens.playlist.PlaylistWire.Slide(image,10000),
                new com.nstut.simplyscreens.playlist.PlaylistWire.Slide(image,10000));
        var initial=new com.nstut.simplyscreens.playlist.PlaylistWire.View("network/test",7,4,3,1,true,true,list,
                List.of(new com.nstut.simplyscreens.playlist.PlaylistWire.Summary(list,"Slides",2)),slides,
                "PLAYING",image,0,1000,"LIST",false,List.of(),List.of());
        screen.updatePlaylist(BlockPos.ZERO,initial.json());screen.smokeSelectTab(1);
        var runtime=new com.nstut.openui.runtime.UiRuntime(stubFont(),new com.nstut.openui.runtime.NativeWidgetHost(){
            public void add(net.minecraft.client.gui.components.AbstractWidget widget) { }
            public void remove(net.minecraft.client.gui.components.AbstractWidget widget) { }
        });
        UIComponent root=screen.buildUI();runtime.setRoot(root);runtime.setViewport(0,0,640,360);root.layoutTree(stubFont(),0,0,640,360);
        var listComponent=findList(root);
        var source=(com.nstut.openui.dnd.Draggable<?>)listComponent.children().get(0).children().get(0).children().get(0);
        runtime.mouseClicked(source.getX()+40,source.getY()+10,0);
        runtime.mouseDragged(source.getX()+45,source.getY()+15,0,5,5);assertTrue(source.isDragging());
        pending.set(screen,2L);
        screen.updatePlaylist(BlockPos.ZERO,new com.nstut.simplyscreens.playlist.PlaylistWire.View("network/test",7,5,3,2,true,true,list,
                initial.playlists(),slides,"PLAYING",image,0,2000,"LIST",false,List.of(),List.of()).json());
        root.layoutTree(stubFont(),0,0,640,360);
        assertFalse(source.isDragging(),"Replacing a row from an authoritative refresh must cancel its drag");
        assertEquals(0,runtime.overlays().size(),"A refreshed row must not leave a preview over the interface");
        var next=(com.nstut.openui.dnd.Draggable<?>)listComponent.children().get(0).children().get(0).children().get(0);
        var destination=listComponent.children().get(1);
        try(var packets=org.mockito.Mockito.mockStatic(com.nstut.simplyscreens.network.PacketRegistries.class)) {
            runtime.mouseClicked(next.getX()+40,next.getY()+10,0);
            runtime.mouseDragged(destination.getX()+40,destination.getY()+10,0,0,30);
            runtime.mouseReleased(destination.getX()+40,destination.getY()+10,0);
            var capture=org.mockito.ArgumentCaptor.forClass(com.nstut.simplyscreens.network.ScreenPlaylistC2SPacket.class);
            packets.verify(()->com.nstut.simplyscreens.network.PacketRegistries.sendToServer(capture.capture()));
            var move=com.nstut.simplyscreens.playlist.PlaylistWire.Request.parse(capture.getValue().json);
            assertEquals("MOVE",move.action());assertEquals(0,move.index());assertEquals(1,move.destination());assertEquals(5,move.libraryRevision());
        }
        runtime.close();
    }


    @Test void emptyTemporarySlidesHideTheClearActionAndPrefetchLabels() throws Exception {
        for (boolean hasFollowing : new boolean[]{false,true}) {
            var screen=new ImageLoadScreen(BlockPos.ZERO);screen.width=320;screen.height=240;
            var list=java.util.UUID.randomUUID();var image=java.util.UUID.randomUUID();
            var pending=ImageLoadScreen.class.getDeclaredField("pendingPlaylistRequest");pending.setAccessible(true);pending.set(screen,1L);
            screen.updatePlaylist(BlockPos.ZERO,new com.nstut.simplyscreens.playlist.PlaylistWire.View("network/test",0,1,1,1,true,true,list,
                    List.of(new com.nstut.simplyscreens.playlist.PlaylistWire.Summary(list,"Slides",1)),
                    List.of(new com.nstut.simplyscreens.playlist.PlaylistWire.Slide(image,10000)),
                    "PLAYING",image,0,1000,hasFollowing?"LIST":"CURRENT",false,List.of(),hasFollowing?List.of(image):List.of()).json());
            screen.smokeSelectTab(2);UIComponent root=screen.buildUI();root.layoutTree(stubFont(),0,0,320,240);
            assertNull(find(root,"playlist.clear_queue"),"An empty queue must not offer an unusable Clear action");
            assertNull(find(root,"playlist.following_slides"),"Image prefetch hints must not be shown as an exact next-slide list");
        }
    }

    @Test void settingsScrollbarCanBeDraggedOutsideThePanelAndReleased() {
        ImageLoadScreen screen=new ImageLoadScreen(BlockPos.ZERO);screen.width=480;screen.height=270;
        screen.smokeSelectTab(3);
        UIComponent root=screen.buildUI();
        var runtime=new com.nstut.openui.runtime.UiRuntime(stubFont(),new com.nstut.openui.runtime.NativeWidgetHost() {
            public void add(net.minecraft.client.gui.components.AbstractWidget widget) {}
            public void remove(net.minecraft.client.gui.components.AbstractWidget widget) {}
        });
        try {
            runtime.setRoot(root);runtime.setViewport(0,0,480,270);runtime.flushFrameTasks();
            root.layoutTree(stubFont(),0,0,480,270);
            ScrollView scroll=findScroll(root);assertNotNull(scroll);
            int x=scroll.getX()+scroll.getWidth()-1,y=scroll.getY();
            assertTrue(runtime.mouseClicked(x,y+2,0));
            assertTrue(runtime.mouseDragged(x+40,y+scroll.getHeight()+40,0,40,scroll.getHeight()+38));
            runtime.flushFrameTasks();assertTrue(scroll.scrollOffset()>0);
            assertTrue(runtime.mouseReleased(x+40,y+scroll.getHeight()+40,0));
            double offset=scroll.scrollOffset();
            runtime.mouseDragged(x+40,y-40,0,0,-100);runtime.flushFrameTasks();
            assertEquals(offset,scroll.scrollOffset());
        } finally {runtime.close();}
    }
    private static ScrollView findScroll(UIComponent node) {
        if(node instanceof ScrollView scroll)return scroll;
        for(UIComponent child:node.children()){var found=findScroll(child);if(found!=null)return found;}
        return null;
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
