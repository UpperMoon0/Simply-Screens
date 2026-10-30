#!/usr/bin/env python3
"""Derive the complete Java 8 runtime from the maintained 1.20.1 implementation.

Only owned mod code is transformed; no Minecraft source or binaries are emitted.
Jabel lowers modern syntax while --release 8 rejects post-Java-8 runtime APIs.
The native adapters override genuinely different game lifecycle and render APIs.
"""
import argparse
import re
from pathlib import Path
from java8_records import lower_records,end_pair
ROOT=Path(__file__).resolve().parents[1]
COMPAT='com.nstut.simplyscreens.compat.Java8'
def java8(text):
    text=lower_records(text)
    for coll,method in [('List','of'),('List','copyOf'),('Set','of'),('Set','copyOf'),('Map','of'),('Map','copyOf')]:
        text=re.sub(r'\b(?:java\.util\.)?'+coll+r'\.'+method+r'\(',COMPAT+'.'+coll.lower()+('Copy' if method=='copyOf' else '')+'(',text)
    text=text.replace('.toList()', '.collect(java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(),java.util.Collections::unmodifiableList))')
    text=re.sub(r'\b(?:java\.nio\.file\.)?Path\.of\(', COMPAT+'.path(', text).replace('Files.readString(',COMPAT+'.readString(').replace('Files.writeString(',COMPAT+'.writeString(')
    text=re.sub(r'([A-Za-z_]\w*(?:\.[A-Za-z_]\w*(?:\([^()]*\))?)*)\.isBlank\(\)',COMPAT+r'.isBlank(\1)',text)
    text=re.sub(r'([A-Za-z_]\w*(?:\.[A-Za-z_]\w*(?:\([^()]*\))?)*)\.strip\(\)',COMPAT+r'.strip(\1)',text)
    text=re.sub(r'(?<![\w.])([a-z]\w*)\.readNBytes\(',COMPAT+r'.readNBytes(\1, ',text)
    return text

def remove_method(text,marker):
    start=text.index(marker);line=text.rfind('\n',0,start)+1
    while line>0:
        previous=text.rfind('\n',0,line-1)+1
        if text[previous:line].strip() in ['@Override','@Nullable','@NotNull']:line=previous
        else:break
    body=text.index('{',start);end=end_pair(text,body,'{','}')
    return text[:line]+text[end+1:]

def image_texture_lifecycle(text):
    """One ownership policy, with thin native texture-manager adapters per generation."""
    helper = 'com.nstut.simplyscreens.client.compat.LegacyTextureLifecycle'
    manager = 'Minecraft.getInstance().getTextureManager()'
    text = text.replace(manager + '.release(location)', helper + '.release(' + manager + ', location, texture)')
    for name in ('imageId', 'errorId'):
        text = text.replace(manager + '.register(' + name + '.toString(), ', helper + '.register(' + manager + ', ' + name + '.toString(), ')
    marker = '        if (texture != null) {\n            ResourceLocation location ='
    if text.count(marker) != 1:
        raise ValueError('Shared image texture registration drift')
    text = text.replace(marker, '        if (texture == errorTexture && texture != null) return errorTextureLocation;\n' + marker)
    start = text.index('        for (Map.Entry<UUID, ResourceLocation> entry : TEXTURE_LOCATIONS.entrySet()) {')
    end = text.index('        IN_MEMORY_CACHE.clear();', start) + len('        IN_MEMORY_CACHE.clear();')
    text = text[:start] + """        for (UUID id : new java.util.HashSet<>(IN_MEMORY_CACHE.keySet())) evictTexture(id);
        TEXTURE_LOCATIONS.clear();
        if (errorTexture != null) {
            """ + helper + '.release(' + manager + """, errorTextureLocation, errorTexture);
            errorTexture = null;
            errorTextureLocation = null;
        }""" + text[end:]
    return text

def legacy_media_hooks(text, name):
    """Opt-in observation/fixture wiring shared by both native source generators."""
    test = 'com.nstut.simplyscreens.testing.'
    client = 'com.nstut.simplyscreens.client.testing.LegacyMediaClientProbe'
    def once(old, new):
        nonlocal text
        if text.count(old) != 1: raise ValueError('Legacy media hook drift: ' + name + ': ' + old)
        text = text.replace(old, new)
    if name == 'SimplyScreens.java':
        once('        ServerTickScheduler.init();', '        ServerTickScheduler.init();\n        ' + test + 'LegacyMediaServerProbe.initialize();')
    if name == 'PacketRegistries.java':
        once('    public static void registerS2CPackets() {', '    public static void registerS2CPackets() {\n        ' + client + '.initialize();')
        once('    private static ResourceLocation packetId(Object packet) {', '    private static ResourceLocation packetId(Object packet) {\n        if (packet instanceof ' + test + 'LegacyMediaProbePacket) return ((' + test + 'LegacyMediaProbePacket) packet).channel();')
        once('    private static void write(Object packet, FriendlyByteBuf buf) {', '    private static void write(Object packet, FriendlyByteBuf buf) {\n        if (packet instanceof ' + test + 'LegacyMediaProbePacket) { ((' + test + 'LegacyMediaProbePacket) packet).write(buf); return; }')
    if name == 'ServerImageManager.java':
        once('                    PacketRegistries.sendToPlayer(player, new UpdateImageListS2CPacket(images));',
             '                    if (' + test + 'LegacyMediaProbeProtocol.ENABLED) ' + test + 'LegacyMediaServerProbe.uploadStored(player, state.level, blockPos, imageId, state.originalName);\n                    PacketRegistries.sendToPlayer(player, new UpdateImageListS2CPacket(images));')
    if name == 'ClientImageManager.java':
        once('                CACHED_EXTENSIONS.put(imageId, extension);',
             '                if (' + test + 'LegacyMediaProbeProtocol.ENABLED) ' + client + '.downloaded(imageId, extension, imageData);\n                CACHED_EXTENSIONS.put(imageId, extension);')
    if name == 'ScreenBlockEntityRenderer.java':
        once('        renderTextureQuad(texture, tile, poseStack, bufferSource, packedOverlay);',
             '        renderTextureQuad(texture, tile, poseStack, bufferSource, packedOverlay);\n        if (' + test + 'LegacyMediaProbeProtocol.ENABLED) ' + client + '.geometrySubmitted(blockEntity, imageId, texture, tile);')
    return text

def convert(text,name):
    text = legacy_media_hooks(text, name)
    if name == 'ClientImageManager.java': text = image_texture_lifecycle(text)
    text=java8(text).replace('dev.architectury.', 'me.shedaniel.architectury.')
    text=text.replace('org.slf4j.LoggerFactory', 'org.apache.logging.log4j.LogManager').replace('org.slf4j.Logger', 'org.apache.logging.log4j.Logger').replace('LoggerFactory.getLogger(', 'LogManager.getLogger(')
    text=text.replace('me.shedaniel.architectury.registry.registries.','me.shedaniel.architectury.registry.')
    text=text.replace('me.shedaniel.architectury.event.events.common.','me.shedaniel.architectury.event.events.')
    text=text.replace('net.minecraft.client.gui.GuiGraphics','com.nstut.openui.graphics.LegacyGuiGraphics')
    text=re.sub(r'\bGuiGraphics\b','LegacyGuiGraphics',text)
    text=text.replace('Component.empty()', 'new net.minecraft.network.chat.TextComponent("")').replace('Component.literal(', 'new net.minecraft.network.chat.TextComponent(').replace('Component.translatable(', 'new net.minecraft.network.chat.TranslatableComponent(')
    text=text.replace('net.minecraft.core.registries.Registries','net.minecraft.core.Registry')
    for typ in ['BLOCK_ENTITY_TYPE','BLOCK','ITEM']:
        text=text.replace('Registries.'+typ,'Registry.'+typ+'_REGISTRY')
    text=re.sub(r'\b(player|sender)\.level\(\)',r'\1.level',text)
    text=text.replace('.serverLevel()', '.getLevel()').replace('player.getInventory()', 'player.inventory')
    text=re.sub(r'\.toArray\((\w+)\[\]::new\)',r'.toArray(new \1[0])',text)
    text=text.replace('.sendSuccess(() -> ', '.sendSuccess(')
    text=text.replace('Block.UPDATE_ALL', '3').replace('getPlayer().level()', 'getPlayer().level')
    text=re.sub(r'(\.chunkMap\.getPlayers\(new ChunkPos\([^\n]*?\), false\))',r'\1.collect(java.util.stream.Collectors.toList())',text)
    text=text.replace('import org.joml.Matrix4f;', 'import com.mojang.math.Matrix4f;').replace('import org.joml.Matrix3f;', 'import com.mojang.math.Matrix3f;')
    text=text.replace('import com.mojang.math.Axis;', 'import com.mojang.math.Vector3f;')
    if 'import com.mojang.math.Vector3f;' in text: text=re.sub(r'(?<![\w.])Axis\.', 'Vector3f.', text)
    if name=='BlockRegistries.java':
        text=text.replace('import net.fabricmc.api.EnvType;', 'import me.shedaniel.architectury.utils.Env;').replace('Platform.getEnv()', 'Platform.getEnvironment()').replace('EnvType.CLIENT', 'Env.CLIENT')
    if name in ('PacketRegistries.java', 'LegacyMediaServerProbe.java', 'LegacyMediaClientProbe.java'):
        text=text.replace('NetworkManager.registerReceiver(', 'com.nstut.simplyscreens.compat.DirectionalReceivers.register(')
    if name in ('ScreenPacketSecurity.java', 'LegacyMediaServerProbe.java'):
        text=text.replace('player.mayInteract(level, pos)', '(player.mayBuild() && !player.isSpectator() && level.mayInteract(player, pos))')
    if name=='ScreenCommands.java':
        text=text.replace('ServerPlayer player = source.getPlayer();', 'ServerPlayer player = source.getEntity() instanceof ServerPlayer ? (ServerPlayer)source.getEntity() : null;')
    if name=='ClientImageManager.java':
        text=text.replace('graphics.blit(location, 0, 0, 0, 0, imageWidth, imageHeight, imageWidth, imageHeight)', 'graphics.blit(location, 0, 0, imageWidth, imageHeight, 0, 0, imageWidth, imageHeight, imageWidth, imageHeight)')
        text=text.replace('inputStream.readAllBytes()', COMPAT+'.readAllBounded(inputStream, com.nstut.simplyscreens.Config.MAX_UPLOAD_SIZE_LIMIT)')
    if name=='LegacyMediaClientProbe.java':
        text=text.replace('client.player.getYRot()', 'client.player.yRot').replace('client.player.getXRot()', 'client.player.xRot')
        text=re.sub(r'client.player.setYRot\((.*)\);', r'client.player.yRot = \1;', text)
        text=re.sub(r'client.player.setXRot\((.*)\);', r'client.player.xRot = \1;', text)
    if name=='PersonalSettingsProbe.java':
        text=text.replace('client.options.guiScale().set(size==2?1:2);', 'client.options.guiScale = size==2?1:2;')
    if name=='DownloadImageFromUrlC2SPacket.java':
        text=re.sub(r'import java.net.http.\w+;\n','',text)
        text=re.sub(r'    private static final HttpClient HTTP_CLIENT[^\n]*\n','',text)
        start=text.index('                        HttpRequest request =')
        end=text.index('                        if (response.statusCode()',start)
        text=text[:start]+"                        com.nstut.simplyscreens.compat.PublicImageHttp.Response response = com.nstut.simplyscreens.compat.PublicImageHttp.get(url);\n"+text[end:]
    if name=='ScreenBlock.java':
        text=text.replace('import net.minecraft.world.level.material.MapColor;\n','')
        text=text.replace('import net.minecraft.world.level.block.entity.BlockEntityTicker;\n','')
        text=remove_method(text,'public <T extends BlockEntity> BlockEntityTicker<T> getTicker(')
        text=text.replace('newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state)', 'newBlockEntity(@NotNull BlockGetter world)')
        text=text.replace('new ScreenBlockEntity(pos, state)','new ScreenBlockEntity()')
        text=text.replace('BlockBehaviour.Properties.of()\n                .mapColor(MapColor.METAL)','BlockBehaviour.Properties.of(net.minecraft.world.level.material.Material.METAL)')
    if name=='ScreenBlockEntity.java':
        text=text.replace('extends BlockEntity', 'extends com.nstut.simplyscreens.compat.LegacyBlockEntity')
        text=text.replace('public class ScreenBlockEntity extends com.nstut.simplyscreens.compat.LegacyBlockEntity {', '''public class ScreenBlockEntity extends com.nstut.simplyscreens.compat.LegacyBlockEntity {
    public ScreenBlockEntity() { this(BlockPos.ZERO, com.nstut.simplyscreens.blocks.BlockRegistries.SCREEN.get().defaultBlockState()); }
    @Override public double getViewDistance() { return Double.MAX_VALUE; }
''')
        text=text.replace('public void setLevel(net.minecraft.world.level.Level level)', 'public void setLevelAndPosition(net.minecraft.world.level.Level level, BlockPos pos)').replace('super.setLevel(level);', 'super.setLevelAndPosition(level, pos);')
        text=text.replace('ClientboundBlockEntityDataPacket.create(this)', 'new ClientboundBlockEntityDataPacket(getBlockPos(), 0, getUpdateTag())')
        old_tag='        CompoundTag tag = new CompoundTag();\n        writePersistentData(tag);\n        return tag;'
        if text.count(old_tag)!=1:raise ValueError('Native screen update tag drift')
        text=text.replace(old_tag,'        return save(new CompoundTag());')
    if name=='BlockEntityRegistries.java':
        text=text.replace('BlockEntityType.Builder.of(ScreenBlockEntity::new','BlockEntityType.Builder.of(ScreenBlockEntity::new')
    if name=='ScreenBlockEntityRenderer.java':
        text=text.replace('implements BlockEntityRenderer<ScreenBlockEntity>', 'extends BlockEntityRenderer<ScreenBlockEntity>')
        text=text.replace('import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;', 'import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;')
        text=text.replace('ScreenBlockEntityRenderer(BlockEntityRendererProvider.Context context) {','ScreenBlockEntityRenderer(BlockEntityRenderDispatcher context) {\n        super(context);')
        text=text.replace('    @Override\n    public int getViewDistance()', '    public int getViewDistance()').replace('    @Override\n    public boolean shouldRender(', '    public boolean shouldRender(')
        text=text.replace('        ScreenBlockEntity anchor = blockEntity.isAnchor()', '        if (!shouldRender(blockEntity, net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().getPosition())) return;\n        ScreenBlockEntity anchor = blockEntity.isAnchor()')
        text=text.replace('RenderType.textPolygonOffset(texture)', 'ScreenColorRenderTypes.world(texture)')
    if name=='ItemRegistries.java':
        text=text.replace('new Item.Properties()', 'new Item.Properties().tab(com.nstut.simplyscreens.creative_tabs.CreativeTabRegistries.CREATIVE_TAB)')
    if name=='GameRendererMixin.java':
        text=text.replace('Lorg/joml/Matrix4f;', 'Lcom/mojang/math/Matrix4f;')
    return text

def generate(output, fabric=True):
    generated=set()
    for folder in ['common/src/main/java','common-1.20.1/src/main/java','common-legacy-game/src/main/java','common-legacy/src/main/java','common-1.16.5/src/main/java']:
        root=ROOT/folder
        for path in root.rglob('*.java'):
            rel=path.relative_to(root);target=output/rel
            text=path.read_text()
            if folder not in ['common-legacy/src/main/java','common-1.16.5/src/main/java']:text=convert(text,path.name)
            if fabric and path.name=='ScreenBlockEntity.java':
                text=text.replace('extends com.nstut.simplyscreens.compat.LegacyBlockEntity','extends com.nstut.simplyscreens.compat.FabricBlockEntity')
                text=remove_method(text,'public ClientboundBlockEntityDataPacket getUpdatePacket(')
            target.parent.mkdir(parents=True,exist_ok=True);target.write_text(text);generated.add(target)
    for path in output.rglob('*.java'):
        if path not in generated:path.unlink()
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);generate(p.parse_args().output)
