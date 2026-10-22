#!/usr/bin/env python3
"""Reuse the maintained 26.1.2 implementation with thin native 26.2 overlays.

Game-neutral services already come directly from common/. This generator keeps
protocol, ownership, API, commands, UI and renderer logic single-sourced between
modern generations. Only owned mod sources are read; no game source is emitted.
"""
import argparse
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = Path('com/nstut/simplyscreens')
NATIVE_REGISTRATIONS = {
    PACKAGE / 'blocks/BlockRegistries.java',
    PACKAGE / 'blocks/entities/BlockEntityRegistries.java',
    PACKAGE / 'items/ItemRegistries.java',
    PACKAGE / 'creative_tabs/CreativeTabRegistries.java',
}

def convert(source):
    # Only the known Minecraft receiver forms are rewritten, never unrelated
    # screen fields or methods. All other gameplay logic remains authoritative.
    receiver = r'(Minecraft\.getInstance\(\)|client|mc)'
    source = re.sub(receiver + r'\.screen\b', r'\1.gui.screen()', source)
    source = re.sub(receiver + r'\.setScreen\(', r'\1.gui.setScreen(', source)
    source = re.sub(receiver + r'\.getOverlay\(\)', r'\1.gui.overlay()', source)
    return source

def canonical_paths(tests=False):
    root = ROOT / 'neoforge-26.1.2/src' / ('test' if tests else 'main') / 'java'
    return root, {
        p.relative_to(root): p for p in root.rglob('*.java')
        if not p.relative_to(root).is_relative_to(PACKAGE / 'neoforge')
        and p.relative_to(root) not in NATIVE_REGISTRATIONS
    }

def generate(output, tests=False):
    _, paths = canonical_paths(tests)
    overlay = ROOT / 'common-26.2/src' / ('test' if tests else 'main') / 'java'
    overrides = {p.relative_to(overlay): p for p in overlay.rglob('*.java')}
    selected = paths | overrides
    written = set()
    for relative, path in sorted(selected.items()):
        source = path.read_text()
        if relative not in overrides:
            source = convert(source)
            if tests:
                marker = 'class ' + path.stem + ' {'
                if source.count(marker) != 1:
                    raise ValueError('Native test declaration drift: ' + str(relative))
                source = source.replace(marker, marker + '\n    @org.junit.jupiter.api.BeforeAll static void bootstrapMinecraft() {\n        net.minecraft.SharedConstants.tryDetectVersion();\n        net.minecraft.server.Bootstrap.bootStrap();\n    }\n', 1)
        target = output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source)
        written.add(target)
    # Stale generated classes must not survive a canonical deletion or rename.
    for path in output.rglob('*.java'):
        if path not in written:
            path.unlink()

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--tests', action='store_true')
    args = parser.parse_args()
    generate(args.output, args.tests)
