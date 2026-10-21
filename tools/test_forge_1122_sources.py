import importlib.util
import json
import pathlib
import sys
import tempfile
import unittest
from unittest.mock import patch
ROOT=pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
import forge_1122_sources as port
import port_resources
class Forge1122SourceTests(unittest.TestCase):
 def test_resource_dispatch_uses_platform_neutral_relative_names(self):
  original=pathlib.Path.relative_to
  def windows_relative(path,*args,**kwargs):
   return pathlib.PureWindowsPath(original(path,*args,**kwargs))
  with tempfile.TemporaryDirectory() as directory:
   output=pathlib.Path(directory)
   with patch.object(pathlib.Path,'relative_to',windows_relative):
    port_resources.generate('1.12.2','forge',output)
   self.assertTrue((output/'assets/simply_screens/recipes/screen.json').is_file())
   self.assertTrue((output/'assets/simply_screens/lang/en_us.lang').is_file())
   variants=json.loads((output/'assets/simply_screens/blockstates/screen.json').read_text())['variants']
   self.assertIn('facing=north,state=0',variants)
   self.assertFalse((output/'data').exists())
 def test_native_variants_keep_all_models_rotations_and_exact_legacy_keys(self):
  canonical=json.loads((ROOT/'common/src/main/resources/assets/simply_screens/blockstates/screen.json').read_text())['variants']
  with tempfile.TemporaryDirectory() as directory:
   output=pathlib.Path(directory);port.generate_resources(output)
   variants=json.loads((output/'assets/simply_screens/blockstates/screen.json').read_text())['variants']
   expected={f'facing={facing},state={state}' for facing in ('north','south','west','east','up','down') for state in range(3)}
   self.assertEqual(expected,set(variants))
   for state in range(3):
    for facing in ('north','south','west','east','up','down'):
     self.assertEqual(canonical[f'state={state},facing={facing}'],variants[f'facing={facing},state={state}'])
   port_resources.generate('1.16.5','fabric',output)
   self.assertEqual(canonical,json.loads((output/'assets/simply_screens/blockstates/screen.json').read_text())['variants'])
 def test_legacy_variant_normalization_rejects_collisions_and_invalid_properties(self):
  for data in [{'a=1,b=2':{},'b=2,a=1':{}},{'a=1,a=2':{}},{'a=1,b=':{}},{'a=1=b':{}}]:
   with self.assertRaises(ValueError):port_resources.legacy_variant_keys(data)
  self.assertEqual({'normal':{},'inventory':{}},port_resources.legacy_variant_keys({'normal':{},'inventory':{}}))
 def test_complete_shared_service_surface(self):
  with tempfile.TemporaryDirectory() as directory:
   output=pathlib.Path(directory);port.generate(output)
   for path in (ROOT/'common/src/main/java').rglob('*.java'):
    self.assertTrue((output/path.relative_to(ROOT/'common/src/main/java')).exists(),str(path))
   for path in (ROOT/'common-1.20.1/src/main/java').rglob('*.java'):
    if path.name not in port.SKIP:self.assertTrue((output/path.relative_to(ROOT/'common-1.20.1/src/main/java')).exists(),str(path))
 def test_text_translation_does_not_change_keys(self):
  text='import net.minecraft.network.chat.Component; Component.literal("gui.simplyscreens.screen.title");'
  result=port.convert(text,'Sample.java')
  self.assertIn('new net.minecraft.util.text.TextComponentString("gui.simplyscreens.screen.title")',result)
  self.assertNotIn('ITextnew',result)
 def test_client_screen_field_not_screen_id_or_translation(self):
  result=port.convert('client.screen; value.screenId(); "gui.simplyscreens.screen.title";','Sample.java')
  self.assertIn('client.currentScreen;',result);self.assertIn('value.screenId()',result);self.assertIn('"gui.simplyscreens.screen.title"',result)
 def test_non_loading_chunk_lookup(self):
  result=port.convert('chunk.getBlockEntity(pos);','Sample.java')
  self.assertIn('chunk.getTileEntity(pos, net.minecraft.world.chunk.Chunk.EnumCreateEntityType.CHECK)',result)
 def test_native_text_and_openui_types(self):
  result=port.convert('import net.minecraft.client.gui.Font; import net.minecraft.client.gui.GuiGraphics; import net.minecraft.network.chat.Component; Component.empty();','Sample.java')
  self.assertIn('com.nstut.openui.compat.LegacyFont',result);self.assertIn('com.nstut.openui.graphics.LegacyGuiGraphics',result);self.assertIn('new net.minecraft.util.text.TextComponentString("")',result)
 def test_authoritative_security_and_native_permission(self):
  source=ROOT/'common-1.20.1/src/main/java/com/nstut/simplyscreens/helpers/ScreenPacketSecurity.java'
  result=port.convert(source.read_text(),source.name)
  for check in ['getPlayerByUUID(player.getUniqueID()) != player','level.isBlockLoaded(pos)','player.capabilities.allowEdit && !player.isSpectator() && level.isBlockModifiable(player, pos)','getDistanceSq(pos.getX()+.5, pos.getY()+.5, pos.getZ()+.5) > 64','Permission.CONTROL','Permission.MANAGE']:self.assertIn(check,result)
 def test_url_downloader_keeps_security_checks(self):
  source=ROOT/'common-1.20.1/src/main/java/com/nstut/simplyscreens/network/DownloadImageFromUrlC2SPacket.java'
  result=port.convert(source.read_text(),source.name)
  self.assertIn('PublicImageHttp.get(url)',result);self.assertNotIn('java.net.http',result);self.assertIn('UrlSecurity',result)
 def test_chunk_update_contains_native_position_and_identity(self):
  source=ROOT/'common-1.20.1/src/main/java/com/nstut/simplyscreens/blocks/entities/ScreenBlockEntity.java'
  result=port.convert(source.read_text(),source.name)
  self.assertIn('NBTTagCompound tag = super.writeToNBT(new NBTTagCompound());',result)
  self.assertIn('public void onLoad()',result)
  self.assertIn('if(anchorPos!=null && anchorPos.equals(previous))anchorPos=next;',result)
  self.assertIn('} else anchorPos = this.pos;',result)
  self.assertNotIn('BlockPos pos = pos.offset(',result)
  self.assertIn('BlockPos pos = this.pos.offset(',result)
 def test_reconciliation_never_initializes_an_unloaded_dimension(self):
  source=(ROOT/'forge-1.12.2/src/main/java/com/nstut/simplyscreens/blocks/entities/ScreenLoadReconciler.java').read_text()
  self.assertIn('DimensionManager.getWorld(',source);self.assertNotIn('server.getWorld(',source)
 def test_real_join_and_personal_settings_probes_are_preserved(self):
  for name in ['UiSmokeTest.java','PersonalSettingsProbe.java']:
   self.assertNotIn(name,port.SKIP)
   source=ROOT/'common-1.20.1/src/main/java/com/nstut/simplyscreens/client/testing'/name
   result=port.convert(source.read_text(),name)
   self.assertNotIn('org.lwjgl.glfw',result);self.assertNotIn('client.getWindow()',result)
  proxy=(ROOT/'forge-1.12.2/src/main/java/com/nstut/simplyscreens/forge/ClientProxy.java').read_text()
  for check in ['client.player==null || client.world==null','UiSmokeTest.tick(client)','LiveJoinTestProtocol.markCompleted()','client::shutdown']:self.assertIn(check,proxy)
 def test_native_production_dependencies_use_real_forge_remapping(self):
  build=(ROOT/'forge-1.12.2/build.gradle').read_text()
  self.assertIn('flatDir',build);self.assertIn('fg.deobf(nativeOpenui)',build);self.assertIn('fg.deobf(nativePacketweave)',build)
  self.assertNotIn('fg.deobf(files(',build)
  self.assertNotIn('inputs.dirs ',build)
 def test_forge_transport_has_distinct_native_direction_types(self):
  source=(ROOT/'forge-1.12.2/src/main/java/com/nstut/simplyscreens/compat/NetworkManager.java').read_text()
  self.assertIn('ServerHandler.class,ServerboundEnvelope.class,0',source)
  self.assertIn('ClientHandler.class,ClientboundEnvelope.class,1',source)
  self.assertLess(source.index('if(handler==null)return;'),source.index('handler.accept(data,context)'))
 def test_native_block_keeps_six_look_directions_and_resistance(self):
  source=(ROOT/'forge-1.12.2/src/main/java/com/nstut/simplyscreens/blocks/ScreenBlock.java').read_text()
  self.assertIn('getFacingFromVector((float)-look.x,(float)-look.y,(float)-look.z)',source)
  self.assertIn('setResistance(10)',source)
 def test_native_resources_and_optional_guide(self):
  with tempfile.TemporaryDirectory() as directory:
   output=pathlib.Path(directory);port.generate_resources(output)
   self.assertIn('tile.simply_screens.screen.name=Screen',(output/'assets/simply_screens/lang/en_us.lang').read_text())
   self.assertTrue((output/'assets/simply_screens/recipes/screen.json').exists())
   self.assertTrue((output/'assets/simply_screens/patchouli_books/guide/book.json').exists())
if __name__=='__main__':unittest.main()
