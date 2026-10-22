#!/usr/bin/env python3
"""Reuse modern resources while translating only native optional-mod gates."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def fabric_conditions(data):
    conditions = data.pop('neoforge:conditions')
    converted = []
    for condition in conditions:
        if set(condition) != {'type', 'modid'} or condition['type'] != 'neoforge:mod_loaded':
            raise ValueError('Unsupported native resource condition: ' + repr(condition))
        converted.append({'condition': 'fabric:all_mods_loaded', 'values': [condition['modid']]})
    if not converted or 'fabric:load_conditions' in data:
        raise ValueError('Missing or ambiguous native resource conditions')
    data['fabric:load_conditions'] = converted
    return data


def generate(loader, output):
    if loader not in ('fabric', 'neoforge'):
        raise ValueError('Unsupported modern loader: ' + loader)
    selected = {}
    for folder in ('neoforge-26.1.2/src/main/resources', 'common-26.2/src/main/resources'):
        root = ROOT / folder
        selected.update({p.relative_to(root): p for p in root.rglob('*') if p.is_file()})
    written = set()
    for relative, source in sorted(selected.items()):
        content = source.read_bytes()
        if loader == 'fabric' and source.suffix == '.json':
            data = json.loads(content)
            if isinstance(data, dict) and 'neoforge:conditions' in data:
                content = (json.dumps(fabric_conditions(data), ensure_ascii=False, indent=2) + '\n').encode()
        target = output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
        written.add(target)
    for path in output.rglob('*'):
        if path.is_file() and path not in written:
            path.unlink()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--loader', choices=('fabric', 'neoforge'), required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    generate(args.loader, args.output)
