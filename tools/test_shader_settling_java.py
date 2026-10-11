"""Execute the actual test-only convergence Java code with screenshot API fixtures."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

COMPILER=os.environ.get('SCREENS_TEST_JAVAC') or shutil.which('javac')
ROOT=Path(__file__).resolve().parents[1]

@unittest.skipUnless(COMPILER,'Java compiler needed for convergence runtime test')
class ShaderSettlingRuntimeTests(unittest.TestCase):
    def test_both_screenshot_apis_and_actual_tile_window(self):
        stubs={
            'com/google/gson/Gson.java':'package com.google.gson; public class Gson { public Object toJsonTree(Object o){return o;} public String toJson(Object o){return "{}";} }',
            'com/google/gson/JsonObject.java':'package com.google.gson; public class JsonObject { public void addProperty(String k,Object v){} public void add(String k,Object v){} }',
            'com/google/gson/JsonArray.java':'package com.google.gson; public class JsonArray { public void add(Object o){} }',
            'net/minecraft/client/Minecraft.java':'package net.minecraft.client; public class Minecraft { public Object getMainRenderTarget(){return null;} }',
            'net/minecraft/client/Screenshot.java':'''package net.minecraft.client;
                import com.mojang.blaze3d.platform.NativeImage;
                public class Screenshot {
                    public static NativeImage takeScreenshot(Object target){return new NativeImage(30);}
                    public static void takeScreenshot(Object target,java.util.function.Consumer<NativeImage> callback){callback.accept(new NativeImage(30));}
                }''',
            'com/mojang/blaze3d/platform/NativeImage.java':'''package com.mojang.blaze3d.platform;
                public class NativeImage implements AutoCloseable {
                    private final int value;
                    public NativeImage(int value){this.value=value;}
                    public void writeToFile(java.nio.file.Path p)throws Exception {
                        var image=new java.awt.image.BufferedImage(960,720,java.awt.image.BufferedImage.TYPE_INT_RGB);
                        var g=image.createGraphics();g.setColor(new java.awt.Color(value,value,value));g.fillRect(0,0,960,720);g.dispose();
                        javax.imageio.ImageIO.write(image,"png",p.toFile());
                    }
                    public void close(){}
                }''',
            'ProbeTest.java':'''import java.lang.reflect.*;
                import com.mojang.blaze3d.platform.NativeImage;
                public class ProbeTest {
                    static Class<?> c;
                    static Field field(String name)throws Exception {var f=c.getDeclaredField(name);f.setAccessible(true);return f;}
                    static void check(boolean value)throws Exception {if(!value)throw new AssertionError("convergence regression");}
                    public static void main(String[] args)throws Exception {
                        c=Class.forName("com.nstut.simplyscreens.testing.visual.ShaderSettling");
                        var accept=c.getDeclaredMethod("accept",NativeImage.class);accept.setAccessible(true);
                        field("current").set(null,"fixture");
                        for(int i=0;i<10;i++) {
                            field("started").setLong(null,System.nanoTime()-(11L+i)*1_000_000_000L);
                            accept.invoke(null,new NativeImage(30));
                            check(field("settled").getBoolean(null)==(i==9));
                        }
                        accept.invoke(null,new NativeImage(31));check(!field("settled").getBoolean(null));
                        for(int i=0;i<9;i++) {accept.invoke(null,new NativeImage(31));}
                        check(field("settled").getBoolean(null));
                        check(!java.nio.file.Files.exists(java.nio.file.Path.of(System.getenv("SS_VISUAL_DIR"),"client-fail.txt")));
                        System.out.println("SHADER_SETTLING_RUNTIME_PASS");
                    }
                }''',
        }
        for capture in ('accept(Screenshot.takeScreenshot(mc.getMainRenderTarget()));',
                        'Screenshot.takeScreenshot(mc.getMainRenderTarget(), ShaderSettling::accept);'):
            with tempfile.TemporaryDirectory() as d:
                root=Path(d)
                for name,source in stubs.items():
                    p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source)
                p=root/'com/nstut/simplyscreens/testing/visual/ShaderSettling.java';p.parent.mkdir(parents=True)
                p.write_text((ROOT/'tools/shader/ShaderSettling.java').read_text().replace('__PROBE__',capture))
                subprocess.run([COMPILER,'-d',str(root/'classes'),*[str(p) for p in root.rglob('*.java')]],check=True,capture_output=True,text=True)
                java=str(Path(COMPILER).resolve().with_name('java'))
                result=subprocess.run([java,'-Djava.awt.headless=true','-cp',str(root/'classes'),'ProbeTest'],
                                      env=os.environ|{'SS_VISUAL_DIR':str(root)},check=True,capture_output=True,text=True)
                self.assertIn('SHADER_SETTLING_RUNTIME_PASS',result.stdout)
