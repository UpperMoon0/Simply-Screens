package com.nstut.simplyscreens.client.testing;

import com.nstut.openui.api.UIComponent;
import com.nstut.simplyscreens.client.screens.ImageLoadScreen;
import com.nstut.simplyscreens.client.ui.SimplyScreensUiPreferences;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import java.util.function.Consumer;

/** Opt-in native input/persistence/hover check; no world or network permissions required. */
public final class PersonalSettingsProbe {
    private static ImageLoadScreen screen;
    private static int phase, ticks;
    private static long lastStep;
    private PersonalSettingsProbe() { }
    public static void tick(Minecraft client) {
        if(phase>=8 || client.gui.overlay()!=null) return;
        try {
            if(screen==null) {
                int size=phase/2;
                client.options.guiScale().set(size==2?1:2);
                org.lwjgl.glfw.GLFW.glfwSetWindowSize(windowHandle(client),size==1?1280:size==3?640:854,size==1?720:480);
                for(String name:new String[]{"resizeDisplay","resizeGui"}) try { Minecraft.class.getMethod(name).invoke(client);break; }catch(NoSuchMethodException ignored) { }
                SimplyScreensUiPreferences.setViewDistance(64);
                SimplyScreensUiPreferences.setThemeMode(phase%2==0?com.nstut.simplyscreens.client.ui.UiThemeMode.DARK:com.nstut.simplyscreens.client.ui.UiThemeMode.LIGHT);
                screen=offlineScreen();client.gui.setScreen(screen);screen.smokeSelectTab(1);ticks=0;
            }
            screen.uiRuntime().mouseScrolled(0,0,0);
            long now=System.nanoTime();if(now-lastStep<50_000_000L)return;lastStep=now;ticks++;
            var slider=keyed(screen.uiRuntime().root(),"settings.view_distance");
            if(ticks>=3 && ticks<=6 && slider!=null) {
                var scroll=keyed(screen.uiRuntime().root(),"settings.content");
                double center=slider.getY()+slider.getHeight()/2.0, viewport=scroll.getY()+scroll.getHeight()/2.0;
                if(center<scroll.getY() || center>=scroll.getY()+scroll.getHeight())
                    screen.uiRuntime().mouseScrolled(scroll.getX()+scroll.getWidth()-1,viewport,(viewport-center)/24.0);
            }
            if(ticks==8) {
                if(slider==null || slider.tooltip()==null || slider.tooltip().getString().startsWith("gui.") || slider.getY()<keyed(screen.uiRuntime().root(),"settings.content").getY() || slider.getY()+slider.getHeight()>keyed(screen.uiRuntime().root(),"settings.content").getY()+keyed(screen.uiRuntime().root(),"settings.content").getHeight())
                    throw new IllegalStateException("Personal render distance must be visible and explained for read-only viewers");
                moveMouse(client,slider.getX()+slider.getWidth()/2.0,slider.getY()+slider.getHeight()/2.0);
            }
            if(ticks==28) capture(client,"display-settings-"+phase+".png");
            if(ticks==30) {
                double x=slider.getX()+slider.getWidth()*.75, y=slider.getY()+slider.getHeight()/2.0;
                screen.uiRuntime().preRender((int)x,(int)y);screen.uiRuntime().mouseClicked(x,y,0);screen.uiRuntime().mouseReleased(x,y,0);
            }
            if(ticks==34) {
                String override=System.getProperty("simplyscreens.ui.config.dir");
                var directory=override==null?client.gameDirectory.toPath().resolve("config"):java.nio.file.Path.of(override);
                int saved=com.nstut.simplyscreens.client.DisplayPreferencesStore.load(directory);
                if(saved<300 || saved!=SimplyScreensUiPreferences.getViewDistance())throw new IllegalStateException("Render distance did not persist through native input");
                client.gui.setScreen(null);
                screen=offlineScreen();client.gui.setScreen(screen);screen.smokeSelectTab(1);
                var field=ImageLoadScreen.class.getDeclaredField("personalViewDistance");field.setAccessible(true);
                if(Math.round((Double)((com.nstut.openui.state.Signal<?>)field.get(screen)).get())!=saved)throw new IllegalStateException("Render distance did not survive reopening");
            }
            if(ticks==38) {
                System.out.println("SIMPLYSCREENS_PERSONAL_SETTINGS_CASE_PASS "+phase);
                client.gui.setScreen(null);screen=null;
                if(++phase==8) {
                    System.out.println("SIMPLYSCREENS_PERSONAL_SETTINGS_PASS cases=8 input=true persisted=true reopen=true readonly=true");client.stop();
                }
            }
        } catch(Exception error) { throw new IllegalStateException("Personal display settings verification failed",error); }
    }
    private static ImageLoadScreen offlineScreen() {
        return new ImageLoadScreen(BlockPos.ZERO) {
            @Override protected void accessAction(String action,String value) { }
            @Override public void refreshImageList() { }
        };
    }
    private static UIComponent keyed(UIComponent root,String key) {
        if(key.equals(root.key()))return root;
        for(var child:root.children()){var found=keyed(child,key);if(found!=null)return found;}return null;
    }
    private static long windowHandle(Minecraft client) throws ReflectiveOperationException {
        for(String name:new String[]{"getWindow","handle"})try{return ((Number)client.getWindow().getClass().getMethod(name).invoke(client.getWindow())).longValue();}catch(NoSuchMethodException ignored) { }
        throw new NoSuchMethodException("Window handle");
    }
    private static void moveMouse(Minecraft client,double x,double y) throws ReflectiveOperationException {
        var method=client.mouseHandler.getClass().getDeclaredMethod("onMove",long.class,double.class,double.class);method.setAccessible(true);
        method.invoke(client.mouseHandler,windowHandle(client),x*client.getWindow().getGuiScale(),y*client.getWindow().getGuiScale());
    }
    private static void capture(Minecraft client,String name) throws ReflectiveOperationException {
        Object target=Minecraft.class.getMethod("getMainRenderTarget").invoke(client);
        Consumer<Component> callback=text -> System.out.println("SIMPLYSCREENS_PERSONAL_SCREENSHOT "+text.getString());
        for(var method:Class.forName("net.minecraft.client.Screenshot").getMethods()) {
            if(!method.getName().equals("grab") || method.getParameterTypes()[0]!=java.io.File.class)continue;
            if(method.getParameterCount()==5){method.invoke(null,client.gameDirectory,name,target,1,callback);return;}
            if(method.getParameterCount()==4){method.invoke(null,client.gameDirectory,name,target,callback);return;}
        }
        throw new NoSuchMethodException("Screenshot.grab");
    }
}
