#!/usr/bin/env python3
"""Validate the optional guide in production artifacts, including physical discovery."""
import argparse
import json
import struct
import tomllib
import zipfile
from pathlib import Path

TARGETS = ('fabric-1.20.1', 'forge-1.20.1', 'fabric-1.21.1', 'neoforge-1.21.1', 'neoforge-26.1.2')
BOOK = 'data/simply_screens/patchouli_books/guide/book.json'
ASSETS = 'assets/simply_screens/'
CONTENT = ASSETS + 'patchouli_books/guide/en_us/'

def validate_recipe(recipe, target):
    if target.startswith('fabric-'):
        expected = [{'condition': 'fabric:all_mods_loaded', 'values': ['patchouli']}]
        actual = recipe.get('fabric:load_conditions')
    else:
        loader = 'forge' if target.startswith('forge-') else 'neoforge'
        expected = [{'type': loader + ':mod_loaded', 'modid': 'patchouli'}]
        actual = recipe.get('conditions' if loader == 'forge' else 'neoforge:conditions')
    if actual != expected:
        raise ValueError('Guide recipe must be gated on Patchouli before deserialization')
    if target.endswith('1.20.1'):
        if recipe.get('type') != 'patchouli:shaped_book_recipe' or recipe.get('book') != 'simply_screens:guide':
            raise ValueError('1.20.1 must use the NBT-aware book recipe serializer')
    elif recipe.get('result', {}).get('components', {}).get('patchouli:book') != 'simply_screens:guide':
        raise ValueError('Modern recipe must retain the Patchouli book component')

def validate_metadata(text, target):
    if target.startswith('fabric-'):
        data = json.loads(text)
        if 'patchouli' in data.get('depends', {}) or data.get('suggests', {}).get('patchouli') != '*':
            raise ValueError('Patchouli must be suggested, never required')
    else:
        records = tomllib.loads(text).get('dependencies', {}).get('simply_screens', [])
        matches = [r for r in records if r.get('modId') == 'patchouli']
        key, value = ('mandatory', False) if target.startswith('forge-') else ('type', 'optional')
        if len(matches) != 1 or matches[0].get(key) != value:
            raise ValueError('Patchouli must have exactly one optional loader dependency')

def validate_texture(data):
    if data[:8] != b'\x89PNG\r\n\x1a\n' or struct.unpack('>II', data[16:24]) != (64, 64) or data[25] != 6:
        raise ValueError('Book texture must be exactly 64x64 RGBA PNG')

def validate_archive(jar, target):
    with zipfile.ZipFile(jar) as z:
        names = set(z.namelist())
        book = json.loads(z.read(BOOK)) # Must be at physical root, not only in nested common jars.
        expected = 'simply_screens:item/guide_book' if target.endswith('1.20.1') else 'simply_screens:guide_book'
        if book.get('model') != expected or book.get('creative_tab') != 'simply_screens:tab' or not book.get('use_resource_pack'):
            raise ValueError('Wrong version-specific model, creative tab, or resource pack discovery')
        model = json.loads(z.read(ASSETS + 'models/item/guide_book.json'))
        if model.get('textures', {}).get('layer0') != 'simply_screens:item/guide_book':
            raise ValueError('Guide model must reference the shipped texture')
        if target.endswith('26.1.2'):
            item = json.loads(z.read(ASSETS + 'items/guide_book.json'))
            if item.get('model', {}).get('model') != 'simply_screens:item/guide_book':
                raise ValueError('26.1.2 needs a matching item model definition')
        validate_texture(z.read(ASSETS + 'textures/item/guide_book.png'))
        folder = 'recipes' if target.endswith('1.20.1') else 'recipe'
        validate_recipe(json.loads(z.read(f'data/simply_screens/{folder}/guide_book.json')), target)
        metadata = 'fabric.mod.json' if target.startswith('fabric-') else ('META-INF/mods.toml' if target.startswith('forge-') else 'META-INF/neoforge.mods.toml')
        validate_metadata(z.read(metadata).decode(), target)
        categories = {n.removeprefix(CONTENT + 'categories/').removesuffix('.json') for n in names if n.startswith(CONTENT + 'categories/') and n.endswith('.json')}
        entries = [n for n in names if n.startswith(CONTENT + 'entries/') and n.endswith('.json')]
        if len(entries) < 10 or len(categories) != 3:
            raise ValueError('Guide chapters or entries missing from production jar')
        for name in entries:
            entry = json.loads(z.read(name))
            if entry['category'].removeprefix('simply_screens:') not in categories:
                raise ValueError(f'Unresolved category in {name}')
        if any(n.startswith('vazkii/patchouli/') or ('patchouli' in n.lower() and n.endswith('.jar')) for n in names):
            raise ValueError('Patchouli must not be bundled')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--module', choices=TARGETS, required=True)
    args = parser.parse_args()
    from assert_production_jar import production_jars
    jars = production_jars(Path(args.module))
    if len(jars) != 1:
        parser.error(f'Expected one production jar, found {jars}')
    validate_archive(jars[0], args.module)
    print(f'{args.module}: optional guide resources, discovery, recipe, model and 64x64 RGBA texture verified')

if __name__ == '__main__':
    main()
