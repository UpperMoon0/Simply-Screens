import hashlib
from pathlib import Path
import tempfile
import unittest
import modern_screen_sources as generator
import modern_screen_resources as resources
import json

class ModernSourceReuseTest(unittest.TestCase):
    def test_native_resources_preserve_canonical_content_and_optional_recipe_gate(self):
        from check_patchouli_jar import validate_recipe
        canonical = generator.ROOT / 'neoforge-26.1.2/src/main/resources'
        overlay = generator.ROOT / 'common-26.2/src/main/resources'
        selected = {p.relative_to(root): p for root in (canonical, overlay) for p in root.rglob('*') if p.is_file()}
        guide = Path('data/simply_screens/recipe/guide_book.json')
        for loader in ('fabric', 'neoforge'):
            with self.subTest(loader=loader), tempfile.TemporaryDirectory() as tmp:
                output = Path(tmp)
                resources.generate(loader, output)
                recipe = json.loads((output / guide).read_text())
                validate_recipe(recipe, loader + '-26.2')
                self.assertEqual(set(selected), {p.relative_to(output) for p in output.rglob('*') if p.is_file()})
                for relative, source in selected.items():
                    if loader == 'fabric' and relative == guide:
                        expected = json.loads(source.read_text())
                        expected.pop('neoforge:conditions')
                        actual = dict(recipe)
                        actual.pop('fabric:load_conditions')
                        self.assertEqual(expected, actual)
                    else:
                        self.assertEqual(source.read_bytes(), (output / relative).read_bytes())
                before = {p.relative_to(output): p.read_bytes() for p in output.rglob('*') if p.is_file()}
                (output / 'stale.json').write_text('{}')
                resources.generate(loader, output)
                self.assertEqual(before, {p.relative_to(output): p.read_bytes() for p in output.rglob('*') if p.is_file()})

    def test_unrecognized_or_ambiguous_native_condition_fails_closed(self):
        for condition in ([], [{'type': 'neoforge:unknown', 'modid': 'patchouli'}],
                          [{'type': 'neoforge:mod_loaded', 'modid': 'patchouli', 'extra': True}]):
            with self.assertRaises(ValueError):
                resources.fabric_conditions({'neoforge:conditions': condition})
        with self.assertRaises(ValueError):
            resources.fabric_conditions({'neoforge:conditions': [{'type': 'neoforge:mod_loaded', 'modid': 'patchouli'}],
                                         'fabric:load_conditions': []})

    def test_complete_modern_runtime_is_single_sourced_and_native_only_overlays_are_small(self):
        _, canonical = generator.canonical_paths()
        overlay = generator.ROOT / 'common-26.2/src/main/java'
        overrides = {p.relative_to(overlay) for p in overlay.rglob('*.java')}
        self.assertEqual(5, len(overrides))
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp)
            generator.generate(output)
            actual = {p.relative_to(output) for p in output.rglob('*.java')}
            self.assertEqual(set(canonical) | overrides, actual)
            self.assertEqual(56, len(actual))
            for relative, source in canonical.items():
                if relative not in overrides:
                    self.assertEqual(generator.convert(source.read_text()), (output / relative).read_text())
            for path in output.rglob('*.java'):
                self.assertNotIn('import net.neoforged.', path.read_text())
            for relative in ('api/ScreenApi.java', 'api/ScreenCommands.java', 'helpers/ScreenPacketSecurity.java',
                             'client/screens/ImageLoadScreen.java', 'network/PacketRegistries.java'):
                self.assertTrue((output / generator.PACKAGE / relative).is_file())

    def test_generation_is_reproducible_and_removes_stale_outputs(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp)
            generator.generate(output)
            def digest():
                return {p.relative_to(output): hashlib.sha256(p.read_bytes()).hexdigest() for p in output.rglob('*.java')}
            first = digest()
            (output / 'Stale.java').write_text('class Stale {}')
            generator.generate(output)
            self.assertEqual(first, digest())
            self.assertFalse((output / 'Stale.java').exists())

    def test_shared_tests_are_reused_with_real_registry_bootstrap(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp)
            generator.generate(output, tests=True)
            tests = list(output.rglob('*.java'))
            self.assertEqual(15, len(tests))
            for path in tests:
                text = path.read_text()
                self.assertEqual(1, text.count('void bootstrapMinecraft()'))
                self.assertIn('net.minecraft.server.Bootstrap.bootStrap()', text)
                self.assertNotIn('PacketWeaveNativeProviderTest', text)

    def test_gui_rewrite_does_not_modify_unrelated_screen_members(self):
        source = 'client.screen; mc.setScreen(x); Minecraft.getInstance().getOverlay(); widget.screen; other.setScreen(x);'
        self.assertEqual('client.gui.screen(); mc.gui.setScreen(x); Minecraft.getInstance().gui.overlay(); widget.screen; other.setScreen(x);', generator.convert(source))

    def test_build_inputs_track_canonical_and_overlay_sources(self):
        build = (generator.ROOT / 'gradle/requested-port.gradle').read_text()
        self.assertIn('../neoforge-26.1.2/src/main/java', build)
        self.assertIn('../neoforge-26.1.2/src/test/java', build)
        self.assertIn("java.srcDirs '../common/src/main/java', generateModernSources", build)
        self.assertIn("java.srcDirs '../common/src/test/java', generateModernTests", build)
        self.assertNotIn("java.srcDirs '../common/src/main/java', '../common-26.2/src/main/java'", build)

if __name__ == '__main__':
    unittest.main()
