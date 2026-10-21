#!/usr/bin/env python3
"""Prime shared 1.12 asset hashes before ForgeGradle 3's per-name downloader.

FG3 schedules aliases concurrently into one file. Priming only aliased objects
avoids that race; its unchanged downloadAssets task still validates every asset.
"""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import urllib.request

INDEX_SHA1 = 'a21e1ded1a24ea1548dd8db0cf30b6acb02655a9'
INDEX_SIZE = 143136
INDEX_URL = 'https://piston-meta.mojang.com/v1/packages/' + INDEX_SHA1 + '/1.12.json'
OBJECT_URL = 'https://resources.download.minecraft.net/'


def download(url, size):
    with urllib.request.urlopen(url, timeout=30) as response:
        data = response.read(size + 1)
    if len(data) != size:
        raise ValueError('Official asset size mismatch: ' + url)
    return data


def verify(data, digest, size):
    if len(data) != size or hashlib.sha1(data).hexdigest() != digest:
        raise ValueError('Official asset content hash or size mismatch: ' + digest)


def prime_aliases(index, asset_root, fetch=download):
    if not isinstance(index, dict) or not isinstance(index.get('objects'), dict):
        raise ValueError('Malformed official asset index')
    objects = list(index['objects'].values())
    for item in objects:
        if (not isinstance(item, dict) or not isinstance(item.get('hash'), str)
                or not re.fullmatch(r'[a-f0-9]{40}', item['hash'])
                or type(item.get('size')) is not int or item['size'] < 0):
            raise ValueError('Invalid official asset identity')
    counts = Counter(item['hash'] for item in objects)
    selected = {}
    for item in objects:
        digest, size = item['hash'], item['size']
        if counts[digest] > 1:
            if digest in selected and selected[digest] != size:
                raise ValueError('Conflicting sizes for aliased official asset')
            selected[digest] = size
    for digest, size in sorted(selected.items()):
        path = asset_root / 'objects' / digest[:2] / digest
        if path.is_file():
            existing = path.read_bytes()
            if len(existing) == size and hashlib.sha1(existing).hexdigest() == digest:
                continue
        data = fetch(OBJECT_URL + digest[:2] + '/' + digest, size)
        verify(data, digest, size)
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(dir=path.parent, prefix=digest + '.', delete=False) as output:
                temporary = Path(output.name)
                output.write(data)
            os.replace(temporary, path)
        finally:
            if temporary is not None and temporary.exists():
                temporary.unlink()
    return len(selected)


def run(asset_root, fetch=download):
    data = fetch(INDEX_URL, INDEX_SIZE)
    verify(data, INDEX_SHA1, INDEX_SIZE)
    return prime_aliases(json.loads(data), asset_root, fetch)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--asset-root', type=Path, required=True)
    args = parser.parse_args()
    count = run(args.asset_root)
    print(f'Primed {count} aliased official asset objects; native Gradle validation is still required.')
