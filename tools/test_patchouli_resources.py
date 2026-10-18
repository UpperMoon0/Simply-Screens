import json
from pathlib import Path
import unittest
from check_patchouli_jar import TARGETS, ASSETS, BOOK, CONTENT, validate_recipe, validate_metadata, validate_texture

ROOT = Path(__file__).resolve().parents[1]
SHARED = ROOT / 'common/src/main/resources'

class PatchouliResourcesTest(unittest.TestCase):
    def test_optional_recipes_are_loader_gated_and_retain_book_identity(self):
        for target in TARGETS:
            folder = 'recipes' if target.endswith('1.20.1') else 'recipe'
            path = ROOT / target / f'src/main/resources/data/simply_screens/{folder}/guide_book.json'
            recipe = json.loads(path.read_text())
            with self.subTest(target=target):
                validate_recipe(recipe, target)
                for key in ('fabric:load_conditions', 'conditions', 'neoforge:conditions'):
                    recipe.pop(key, None)
                with self.assertRaisesRegex(ValueError, 'gated'):
                    validate_recipe(recipe, target)
        self.assertFalse((SHARED / 'data/simply_screens/recipe/guide_book.json').exists(), 'No unconditional shared recipe may leak into another loader')

    def test_metadata_never_requires_patchouli(self):
        for target in TARGETS:
            suffix = 'fabric.mod.json' if target.startswith('fabric-') else ('META-INF/mods.toml' if target.startswith('forge-') else 'META-INF/neoforge.mods.toml')
            base = 'src/main/templates' if target.endswith('26.1.2') else 'src/main/resources'
            text = (ROOT / target / base / suffix).read_text().replace("${mod_id}", "simply_screens")
            with self.subTest(target=target):
                validate_metadata(text, target)
                if target.startswith('fabric-'):
                    data = json.loads(text); data.setdefault('depends', {})['patchouli'] = '*'; text = json.dumps(data)
                elif target.startswith('forge-'):
                    text = text.replace('mandatory = false', 'mandatory = true')
                else:
                    text = text.replace('type = "optional"', 'type = "required"')
                with self.assertRaises(ValueError): validate_metadata(text, target)

    def test_guide_references_resolve_and_text_has_conservative_page_budget(self):
        book = json.loads((SHARED / BOOK).read_text())
        self.assertTrue(book['use_resource_pack'])
        self.assertEqual('simply_screens:tab', book['creative_tab'])
        content = SHARED / CONTENT
        categories = {p.stem for p in (content / 'categories').glob('*.json')}
        entries = list((content / 'entries').glob('*.json'))
        self.assertEqual(3, len(categories)); self.assertEqual(10, len(entries))
        for path in entries:
            entry = json.loads(path.read_text())
            self.assertIn(entry['category'].split(':')[1], categories)
            for page in entry['pages']:
                if page['type'] == 'patchouli:text':
                    self.assertLessEqual(len(page['text']), 230, path.name)
                    self.assertLessEqual(len(page['title']), 22, path.name)
                else:
                    self.assertEqual('patchouli:crafting', page['type'])
                    self.assertIn(page['recipe'], ('simply_screens:screen', 'simply_screens:guide_book'))

    def test_generated_texture_is_exactly_64_by_64_rgba(self):
        validate_texture((SHARED / ASSETS / 'textures/item/guide_book.png').read_bytes())
        with self.assertRaises(ValueError): validate_texture(b'not a PNG')
