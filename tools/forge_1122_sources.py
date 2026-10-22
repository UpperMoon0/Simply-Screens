#!/usr/bin/env python3
"""Backport all maintained Screens logic to native 1.12.2 MCP39 adapters.

Only this repository's code is transformed. Minecraft code is not distributed.
Native lifecycle, packets, tile/block APIs, rendering and commands are supplied by
forge-1.12.2. Java8 runtime compatibility is checked by javac --release 8.
"""
import argparse,re
from pathlib import Path
import legacy_screen_sources as legacy
ROOT=Path(__file__).resolve().parents[1]
C='com.nstut.simplyscreens.compat.'
TYPES={
 'net.minecraft.core.BlockPos':'net.minecraft.util.math.BlockPos',
 'net.minecraft.core.Direction':'net.minecraft.util.EnumFacing',
 'net.minecraft.core.SectionPos':C+'SectionPos',
 'net.minecraft.world.level.ChunkPos':'net.minecraft.util.math.ChunkPos',
 'net.minecraft.world.level.Level':'net.minecraft.world.World',
 'net.minecraft.server.level.ServerLevel':'net.minecraft.world.WorldServer',
 'net.minecraft.server.level.ServerPlayer':'net.minecraft.entity.player.EntityPlayerMP',
 'net.minecraft.world.entity.player.Player':'net.minecraft.entity.player.EntityPlayer',
 'net.minecraft.world.level.block.entity.BlockEntity':'net.minecraft.tileentity.TileEntity',
 'net.minecraft.world.level.chunk.LevelChunk':'net.minecraft.world.chunk.Chunk',
 'net.minecraft.world.level.block.state.BlockState':'net.minecraft.block.state.IBlockState',
 'net.minecraft.world.level.block.Block':'net.minecraft.block.Block',
 'net.minecraft.world.level.block.state.properties.DirectionProperty':'net.minecraft.block.properties.PropertyDirection',
 'net.minecraft.world.level.block.state.properties.IntegerProperty':'net.minecraft.block.properties.PropertyInteger',
 'net.minecraft.world.level.BlockGetter':'net.minecraft.world.IBlockAccess',
 'net.minecraft.world.phys.AABB':'net.minecraft.util.math.AxisAlignedBB',
 'net.minecraft.nbt.CompoundTag':'net.minecraft.nbt.NBTTagCompound',
 'net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket':'net.minecraft.network.play.server.SPacketUpdateTileEntity',
 'net.minecraft.network.FriendlyByteBuf':C+'LegacyPacketBuffer',
 'net.minecraft.resources.ResourceLocation':'net.minecraft.util.ResourceLocation',
 'net.minecraft.network.chat.Component':'net.minecraft.util.text.ITextComponent',
 'net.minecraft.client.gui.GuiGraphics':'com.nstut.openui.graphics.LegacyGuiGraphics',
 'net.minecraft.client.gui.Font':'com.nstut.openui.compat.LegacyFont',
 'net.minecraft.client.renderer.texture.DynamicTexture':C+'LegacyDynamicTexture',
 'com.mojang.blaze3d.platform.NativeImage':C+'LegacyImage',
 'dev.architectury.networking.NetworkManager':C+'NetworkManager',
 'dev.architectury.platform.Platform':C+'Platform',
 'dev.architectury.utils.Env':C+'Env',
 'dev.architectury.event.events.common.LifecycleEvent':C+'LifecycleEvent',
 'dev.architectury.event.events.common.TickEvent':C+'TickEvent',
 'dev.architectury.event.events.client.ClientTickEvent':C+'ClientTickEvent',
 'net.minecraft.world.level.storage.LevelResource':C+'LevelResource',
 'net.minecraft.world.item.ItemStack':'net.minecraft.item.ItemStack',
 'net.minecraft.world.item.Item':'net.minecraft.item.Item',
 'net.minecraft.client.gui.screens.Screen':'net.minecraft.client.gui.GuiScreen',
}
SKIP={'ScreenBlock.java','ClientScreenBlock.java','BlockRegistries.java','BlockEntityRegistries.java','CreativeTabRegistries.java','ItemRegistries.java','ScreenBlockEntityRenderer.java','ScreenColorRenderTypes.java','ScreenColorRestore.java','GameRendererMixin.java','ScreenCommands.java','ScreenLoadReconciler.java'}
def convert(text,name):
 text=legacy.legacy_media_hooks(text,name)
 if name=='ClientImageManager.java':text=legacy.image_texture_lifecycle(text)
 text=legacy.java8(text)
 text=text.replace('org.slf4j.LoggerFactory','org.apache.logging.log4j.LogManager').replace('org.slf4j.Logger','org.apache.logging.log4j.Logger').replace('LoggerFactory.getLogger(', 'LogManager.getLogger(')
 text=text.replace('JsonParser.parseString(', 'new com.google.gson.JsonParser().parse(')
 if name=='DownloadImageFromUrlC2SPacket.java':
  text=re.sub(r'import java.net.http.\w+;\n','',text)
  text=re.sub(r'    private static final HttpClient HTTP_CLIENT[^\n]*\n','',text)
  start=text.index('                        HttpRequest request =');end=text.index('                        if (response.statusCode()',start)
  text=text[:start]+f'                        {C}PublicImageHttp.Response response = {C}PublicImageHttp.get(url);\n'+text[end:]
 aliases={}
 def imported(m):
  typ=m[1]
  if typ in TYPES:
   aliases[typ.rsplit('.',1)[1]]=TYPES[typ].rsplit('.',1)[1]
   return 'import '+TYPES[typ]+';'
  return m[0]
 text=re.sub(r'import ([\w.]+);',imported,text)
 for a,b in sorted(TYPES.items(),key=lambda p:-len(p[0])):text=re.sub(r'\b'+re.escape(a)+r'\b',b,text)
 for a,b in aliases.items():text=re.sub(r'(?<![\w.])'+a+r'\b',b,text)
 text=re.sub(r'\bComponent\.literal\(', 'new net.minecraft.util.text.TextComponentString(',text)
 text=re.sub(r'\bComponent\.translatable\(', 'new net.minecraft.util.text.TextComponentTranslation(',text)
 text=re.sub(r'\bComponent\.empty\(\)', 'new net.minecraft.util.text.TextComponentString("")',text)
 text=text.replace('ITextComponent.literal(', 'new net.minecraft.util.text.TextComponentString(').replace('ITextComponent.translatable(', 'new net.minecraft.util.text.TextComponentTranslation(').replace('ITextComponent.empty()', 'new net.minecraft.util.text.TextComponentString("")')
 text=re.sub(r'        dev.architectury.event.events.common.PlayerEvent.PLAYER_QUIT.register[^\n]+\n', '',text)
 text=text.replace('Minecraft.getInstance()', 'Minecraft.getMinecraft()')
 text=re.sub(r'\b(client|mc)\.level\b',r'\1.world',text)
 text=text.replace('online.getName().getUnformattedText()', 'online.getName()')
 text=re.sub(r'\.toArray\((\w+)\[\]::new\)',r'.toArray(new \1[0])',text)
 text=text.replace('inputStream.readAllBytes()', 'com.nstut.simplyscreens.compat.LegacyStreams.readAll(inputStream)')
 text=re.sub(r'chunk\.getTileEntity\(([^()]*)\)',r'chunk.getTileEntity(\1, net.minecraft.world.chunk.Chunk.EnumCreateEntityType.CHECK)',text)
 text=text.replace('entity.getBlockState()', 'screen.getBlockState()').replace('.hasProperty(', '.getPropertyKeys().contains(')
 text=re.sub(r'(\w+(?:\.getBlockState\(\))?)\.hasProperty\(([^()]+)\)',r'\1.getPropertyKeys().contains(\2)',text)

 text=text.replace('.gameDirectory', '.gameDir')
 text=text.replace('Minecraft.getMinecraft().screen', 'Minecraft.getMinecraft().currentScreen')
 text=re.sub(r'\b(mc|client)\.screen\b', r'\1.currentScreen',text)
 # Game member names, kept distinct from our own records and services.
 methods={'getUUID':'getUniqueID','serverLevel':'getServerWorld','getTickCount':'getTickCounter','getBlockEntity':'getTileEntity','hasChunkAt':'isBlockLoaded','getChunkSource':'getChunkProvider','getChunkNow':'getLoadedChunk','getChunkAt':'getChunk','getBlockPos':'getPos','setChanged':'markDirty','isRemoved':'isInvalid','relative':'offset','immutable':'toImmutable','asLong':'toLong','getCounterClockWise':'rotateYCCW','getClockWise':'rotateY','getStepX':'getXOffset','getStepY':'getYOffset','getStepZ':'getZOffset','sendBlockUpdated':'notifyBlockUpdate','setBlock':'setBlockState','distanceToSqr':'getDistanceSq','isSameThread':'isCallingFromMinecraftThread','getWorldPath':'getWorldPath','getConnection':'getConnection','setScreen':'displayGuiScreen'}
 for a,b in methods.items():text=re.sub(r'\.'+a+r'\(','.'+b+'(',text)
 text=text.replace('.isClientSide()', '.isRemote').replace('.isClientSide', '.isRemote')
 text=re.sub(r'\b(player|sender|client)\.level\(\)',r'\1.world',text)
 text=text.replace('Minecraft.getMinecraft().level', 'Minecraft.getMinecraft().world')
 text=text.replace('player.getInventory()', 'player.inventory')
 text=text.replace('.hasPermissions(2)', '.canUseCommand(2, "screens")')
 text=text.replace('player.mayInteract(level, pos)', '(player.capabilities.allowEdit && !player.isSpectator() && level.isBlockModifiable(player, pos))')
 text=text.replace('server.getAllLevels()', 'java.util.Arrays.asList(server.worlds)')
 text=text.replace('.getPlayerList().getPlayer(', '.getPlayerList().getPlayerByUUID(')
 text=text.replace('.getServer()', '.getMinecraftServer()')
 text=text.replace('.getTextureManager().loadTexture(imageId.toString(),', '.getTextureManager().getDynamicTextureLocation(imageId.toString(),').replace('.getTextureManager().loadTexture(errorId.toString(),', '.getTextureManager().getDynamicTextureLocation(errorId.toString(),')
 text=text.replace('.getPlayers()', '.getPlayers()')
 text=re.sub(r'(\w+)\.getChunkProvider\(\)\.chunkMap\.getPlayers\(new ChunkPos\(([^()]*)\), false\)',C+r'NativeWorld.tracking(\1, \2)',text)
 text=text.replace('server.getWorldPath(LevelResource.ROOT)', C+'NativeWorld.root(server)')
 text=text.replace('level.dimension().location().toString()', C+'NativeWorld.dimension(level)')
 text=text.replace('.getString()', '.getUnformattedText()')
 text=text.replace('Minecraft.getMinecraft().execute(', 'Minecraft.getMinecraft().addScheduledTask(')
 text=text.replace('server.execute(', 'server.addScheduledTask(')
 text=text.replace('Minecraft.getMinecraft().getTextureManager().register(', 'Minecraft.getMinecraft().getTextureManager().loadTexture(')
 text=text.replace('Minecraft.getMinecraft().getTextureManager().release(', 'Minecraft.getMinecraft().getTextureManager().deleteTexture(')
 text=text.replace('Minecraft.getMinecraft().font', 'com.nstut.openui.compat.LegacyFont.current()')
 text=text.replace('BlockPos.of(', 'BlockPos.fromLong(')
 if name=='ScreenRegistry.java':
  start=text.index('        try {\n            if (level.dimension()')
  text=text[:start]+'        return com.nstut.simplyscreens.compat.NativeWorld.dimension(level);\n    }\n}\n'
 if name=='ScreenBlockEntity.java':
  text=text.replace('extends TileEntity', 'extends '+C+'LegacyBlockEntity')
  text=text.replace('public class ScreenBlockEntity extends '+C+'LegacyBlockEntity {', 'public class ScreenBlockEntity extends '+C+'LegacyBlockEntity {\n    @Override public void onLoad() { super.onLoad(); if (world instanceof net.minecraft.world.WorldServer) { com.nstut.simplyscreens.helpers.ServerImageManager.trackLoadedScreen(this); ScreenLoadReconciler.enqueue((net.minecraft.world.WorldServer)world, getPos()); } }')
  text=re.sub(r'(?<![\w.])worldPosition\b','this.pos',text);text=text.replace('.worldPosition','.pos');text=re.sub(r'\blevel\b','world',text)
  text=text.replace('super(BlockEntityRegistries.SCREEN.get(), pos, state);','super();\n        setPos(pos);')
  # Forge calls setWorld before setPos; onLoad above is the real registration boundary.
  start=text.index('    @Override\n    public void setLevel(')
  end=text.index('    @Override\n    public void setRemoved()',start)
  text=text[:start]+text[end:]

  text=text.replace('public ScreenBlockEntity(BlockPos pos, IBlockState state) {','public ScreenBlockEntity() { this(BlockPos.ORIGIN, null); }\n    @Override public void setPos(BlockPos next) { BlockPos previous=getPos();super.setPos(next);if(anchorPos!=null && anchorPos.equals(previous))anchorPos=next; }\n    public ScreenBlockEntity(BlockPos pos, IBlockState state) {')
  text=text.replace('SPacketUpdateTileEntity.create(this)','new SPacketUpdateTileEntity(getPos(), 0, getUpdateTag())')
  text=text.replace('NBTTagCompound tag = new NBTTagCompound();\n        writePersistentData(tag);', 'NBTTagCompound tag = super.writeToNBT(new NBTTagCompound());\n        writePersistentData(tag);')
  text=text.replace('Block.UPDATE_ALL','3')
  text=text.replace('if (world == null || world.isRemote) return;\n\n        com.nstut.simplyscreens.helpers.ScreenAccessService.refreshMedia(this);', 'if (world == null || world.isRemote || !isAnchor()) return;\n\n        com.nstut.simplyscreens.helpers.ScreenAccessService.refreshMedia(this);')
  text=re.sub(r'(?<![\w.])setChanged\(', 'markDirty(',text)
  text=re.sub(r'(?<![\w.])isRemoved\(', 'isInvalid(',text)
  text=text.replace('.getValue(', '.getValue(').replace('.setValue(', '.withProperty(')
  for a,b in {'putUUID':'setUniqueId','hasUUID':'hasUniqueId','getUUID':'getUniqueId','putLong':'setLong','putBoolean':'setBoolean','putString':'setString','putInt':'setInteger','getInt':'getInteger','contains':'hasKey'}.items():text=text.replace('tag.'+a+'(', 'tag.'+b+'(')
  text=text.replace('tag.getUniqueID(', 'tag.getUniqueId(')
  text=text.replace('private void writePersistentData(NBTTagCompound tag) {', 'private void writePersistentData(NBTTagCompound tag) {\n        tag.setBoolean("legacyErrorState", anchorPos == null);')
  marker='\n    }\n\n    @Override\n    public SPacketUpdateTileEntity getUpdatePacket()'
  text=text.replace(marker,'\n        if (tag.getBoolean("legacyErrorState")) anchorPos = null;'+marker)
  text=text.replace('            );\n        }\n        if (tag.getBoolean("legacyErrorState"))', '            );\n        } else anchorPos = this.pos;\n        if (tag.getBoolean("legacyErrorState"))')

 if name in ['ImageLoadScreen.java','SimplyScreensUiScreen.java']:
  text=text.replace('protected void init()', 'public void initGui()').replace('super.init()', 'super.initGui()')
  text=text.replace('public void tick()', 'public void updateScreen()').replace('super.tick()', 'super.updateScreen()')
  text=text.replace('public void removed()', 'public void onGuiClosed()').replace('super.removed()', 'super.onGuiClosed()')
  text=text.replace('public boolean isPauseScreen()', 'public boolean doesGuiPauseGame()')
  if name=='ImageLoadScreen.java':
   text=re.sub(r'import org.lwjgl.(?:PointerBuffer|system.MemoryStack|util.tinyfd.TinyFileDialogs);\n','',text)
   start=text.index('        var client=Minecraft.getMinecraft(); var window=client.getWindow();')
   end=text.index('\n    }',start)
   text=text[:start]+'''        var client=Minecraft.getMinecraft();
        return Ui.contextMenu(org.lwjgl.input.Mouse.getX()*width/client.displayWidth,
                height-org.lwjgl.input.Mouse.getY()*height/client.displayHeight-1);'''+text[end:]
   start=text.index('        var client = Minecraft.getMinecraft();\n        var mouse = client.mouseHandler;')
   end=text.index('        for (var policy :',start)
   text=text[:start]+'        var menu = playlistMenu();\n'+text[end:]
   start=text.index('        try (MemoryStack stack = MemoryStack.stackPush())')
   b=text.index('            if (filePath == null)',start)
   text=text[:start]+'''        {
            String filePath = com.nstut.simplyscreens.compat.NativeFileDialog.chooseImage();
'''+text[b:]
 if name in ('LegacyMediaServerProbe.java', 'LegacyMediaClientProbe.java'):
  text=text.replace('player.blockPosition()', 'player.getPosition()').replace('client.player.blockPosition()', 'client.player.getPosition()')
  text=text.replace('.isEmptyBlock(', '.isAirBlock(').replace('.defaultBlockState()', '.getDefaultState()').replace('.setValue(', '.withProperty(')
  text=text.replace('active.level.removeBlock(active.pos, false)', 'active.level.setBlockToAir(active.pos)').replace('level.removeBlock(pos, false)', 'level.setBlockToAir(pos)')
  text=text.replace('client.player.getYRot()', 'client.player.rotationYaw').replace('client.player.getXRot()', 'client.player.rotationPitch')
  text=text.replace('client.player.getX()', 'client.player.posX').replace('client.player.getZ()', 'client.player.posZ').replace('client.player.getEyeY()', '(client.player.posY + client.player.getEyeHeight())')
  text=re.sub(r'client.player.setYRot\((.*)\);', r'client.player.rotationYaw = \1;', text)
  text=re.sub(r'client.player.setXRot\((.*)\);', r'client.player.rotationPitch = \1;', text)
  text=text.replace('pixels.getPixelRGBA(x, y)', 'LegacyMediaProbeProtocol.argbToAbgr(pixels.buffered().getRGB(x, y))').replace('texture.getId()', 'texture.getGlTextureId()')
  if name=='LegacyMediaClientProbe.java':
   text=text.replace('if (evidence.passed()) {', 'if (evidence.passed()) {\n            NativePlacedModelProbe.verifyAndCapture(client, screen, session);')
 if name=='UiSmokeTest.java':
  text=text.replace('client.player.blockPosition()', 'client.player.getPosition()')
  text=text.replace('client.currentScreen.resize(client, client.getWindow().getGuiScaledWidth(),', 'client.currentScreen.onResize(client, new net.minecraft.client.gui.ScaledResolution(client).getScaledWidth(),')
  text=text.replace('client.getWindow().getGuiScaledHeight()', 'new net.minecraft.client.gui.ScaledResolution(client).getScaledHeight()')
 if name=='PersonalSettingsProbe.java':
  text=text.replace('phase>=8 || client.getOverlay()!=null', 'phase>=8')
  text=text.replace('client.options.guiScale().set(size==2?1:2)', 'client.gameSettings.guiScale=size==2?1:2')
  text=text.replace('org.lwjgl.glfw.GLFW.glfwSetWindowSize(windowHandle(client),size==1?1280:size==3?640:854,size==1?720:480)', 'org.lwjgl.opengl.Display.setDisplayMode(new org.lwjgl.opengl.DisplayMode(size==1?1280:size==3?640:854,size==1?720:480))')
  text=re.sub(r'                for\(String name:new String\[\]\{\"resizeDisplay\",\"resizeGui\"\}\).*?\n','',text)
  text=text.replace('BlockPos.ZERO','BlockPos.ORIGIN').replace('client.stop()', 'client.shutdown()')
  start=text.index('    private static long windowHandle(')
  text=text[:start]+'''    private static void moveMouse(Minecraft client,double x,double y) {
        int scale=new net.minecraft.client.gui.ScaledResolution(client).getScaleFactor();
        org.lwjgl.input.Mouse.setCursorPosition((int)(x*scale),client.displayHeight-(int)(y*scale)-1);
    }
    private static void capture(Minecraft client,String name) {
        System.out.println("SIMPLYSCREENS_PERSONAL_SCREENSHOT "+net.minecraft.util.ScreenShotHelper.saveScreenshot(client.gameDir,name,client.displayWidth,client.displayHeight,client.getFramebuffer()).getUnformattedText());
    }
}
'''
 text=re.sub(r'chunk\.getTileEntity\(([^(),]*)\)',r'chunk.getTileEntity(\1, net.minecraft.world.chunk.Chunk.EnumCreateEntityType.CHECK)',text)
 text=text.replace('online.getName().getUnformattedText()', 'online.getName()').replace('player.getName().getUnformattedText()', 'player.getName()')
 text=text.replace('.getPlayer().level()', '.getPlayer().world')
 text=re.sub(r'\b(player|sender|p|online|onlinePlayer)\.getMinecraftServer\(\)',r'\1.getServer()',text)
 text=text.replace('server::execute','server::addScheduledTask')
 text=text.replace('.getPlayerByName(', '.getPlayerByUsername(').replace('.displayClientMessage(', '.sendStatusMessage(')
 text=text.replace('.getTextureManager().loadTexture(imageId.toString(),', '.getTextureManager().getDynamicTextureLocation(imageId.toString(),').replace('.getTextureManager().loadTexture(errorId.toString(),', '.getTextureManager().getDynamicTextureLocation(errorId.toString(),')
 text=text.replace('graphics.blit(location, 0, 0, 0, 0, imageWidth, imageHeight, imageWidth, imageHeight)', 'graphics.blit(location, 0, 0, imageWidth, imageHeight, 0, 0, imageWidth, imageHeight, imageWidth, imageHeight)')
 text=text.replace('serverLevel.getMinecraftServer().getAllLevels()', 'java.util.Arrays.asList(serverLevel.getMinecraftServer().worlds)')
 text=text.replace('level.getChunkProvider().chunkMap.getPlayers(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4), false)', 'com.nstut.simplyscreens.compat.NativeWorld.tracking(level, pos)')
 return text

def generate(output):
 generated=set()
 for folder in ['common/src/main/java','common-1.20.1/src/main/java','common-legacy-game/src/main/java','common-legacy/src/main/java']:
  root=ROOT/folder
  for source in root.rglob('*.java'):
   if source.name in SKIP:continue
   relative=source.relative_to(root);target=output/relative
   text=source.read_text();text=convert(text,source.name) if folder!='common-legacy/src/main/java' else text
   target.parent.mkdir(parents=True,exist_ok=True);target.write_text(text);generated.add(target)
 for path in output.rglob('*.java'):
  if path not in generated:path.unlink()
def generate_tests(output):
 import legacy_screen_tests
 legacy_screen_tests.generate(output)
 for path in output.rglob('*.java'):
  text=path.read_text().replace('org.slf4j.LoggerFactory','org.apache.logging.log4j.LogManager').replace('org.slf4j.Logger','org.apache.logging.log4j.Logger').replace('LoggerFactory.getLogger(', 'LogManager.getLogger(')
  path.write_text(text)
def generate_resources(output):
 import json
 import port_resources
 port_resources.generate('1.12.2','forge',output)
 root=output/'assets/simply_screens'
 language=root/'lang/en_us.lang'
 language.write_text(language.read_text()+'\ntile.simply_screens.screen.name=Screen\n')
 book=json.loads((ROOT/'common/src/main/resources/data/simply_screens/patchouli_books/guide/book.json').read_text())
 book['creative_tab']='simply_screens';book['model']='simply_screens:guide_book';book.pop('use_resource_pack',None)
 target=root/'patchouli_books/guide/book.json';target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(book,indent=2)+'\n')
 # Keep guide instructions consistent with the native quartz recipe.
 for path in (root/'patchouli_books').rglob('*.json'):
  text=path.read_text().replace('minecraft:amethyst_shard','minecraft:quartz').replace('amethyst shard','quartz')
  path.write_text(text)
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--output',required=True,type=Path);p.add_argument('--tests',action='store_true');p.add_argument('--resources',action='store_true');args=p.parse_args();(generate_tests if args.tests else generate_resources if args.resources else generate)(args.output)
