import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock
import prefetch_legacy_asset_aliases as assets


class LegacyAssetAliasesTest(unittest.TestCase):
    def fixture(self):
        content = b'official fixture'
        digest = hashlib.sha1(content).hexdigest()
        item = {'hash': digest, 'size': len(content)}
        return content, digest, {'objects': {'icons/minecraft.icns': item, 'minecraft/icons/minecraft.icns': dict(item),
                                           'unique': {'hash': 'a' * 40, 'size': 1}}}

    def test_aliases_download_once_by_hash_and_valid_cache_is_preserved(self):
        content, digest, index = self.fixture()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fetch = Mock(return_value=content)
            self.assertEqual(1, assets.prime_aliases(index, root, fetch))
            fetch.assert_called_once_with(assets.OBJECT_URL + digest[:2] + '/' + digest, len(content))
            self.assertEqual(content, (root / 'objects' / digest[:2] / digest).read_bytes())
            self.assertEqual(1, assets.prime_aliases(index, root, fetch))
            self.assertEqual(1, fetch.call_count)
            self.assertEqual(1, len([p for p in root.rglob('*') if p.is_file()]))

    def test_corruption_fails_before_cache_replacement(self):
        content, digest, index = self.fixture()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); path = root / 'objects' / digest[:2] / digest
            path.parent.mkdir(parents=True); path.write_bytes(b'old bad cache')
            with self.assertRaisesRegex(ValueError, 'content hash or size'):
                assets.prime_aliases(index, root, Mock(return_value=b'bad download'))
            self.assertEqual(b'old bad cache', path.read_bytes())
            assets.prime_aliases(index, root, Mock(return_value=content))
            self.assertEqual(content, path.read_bytes())

    def test_wrong_index_pin_is_rejected_before_any_object_download(self):
        fetch = Mock(return_value=b'{}')
        with tempfile.TemporaryDirectory() as tmp, self.assertRaises(ValueError):
            assets.run(Path(tmp), fetch)
        fetch.assert_called_once_with(assets.INDEX_URL, assets.INDEX_SIZE)

    def test_malformed_index_and_escaping_paths_fail_before_download(self):
        _, digest, _ = self.fixture()
        bad = [[], {}, {'objects': []}, {'objects': {'x': None}},
               {'objects': {'x': {'hash': '../../outside', 'size': 1}}},
               {'objects': {'x': {'hash': digest, 'size': True}}},
               {'objects': {'x': {'hash': digest, 'size': -1}}},
               {'objects': {'x': {'hash': digest, 'size': 1}, 'y': {'hash': digest, 'size': 2}}}]
        for index in bad:
            with self.subTest(index=index), tempfile.TemporaryDirectory() as tmp:
                fetch = Mock()
                with self.assertRaises(ValueError):
                    assets.prime_aliases(index, Path(tmp), fetch)
                fetch.assert_not_called()
                self.assertEqual([], list(Path(tmp).rglob('*')))

    def test_native_asset_verification_still_runs_after_prefetch(self):
        workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/requested-generations.yml').read_text()
        step = workflow.split('name: Prefetch official legacy assets', 1)[1].split('      - name:', 1)[0]
        self.assertLess(step.index('tools/prefetch_legacy_asset_aliases.py'), step.index(' downloadAssets '))
        self.assertIn('${GRADLE_USER_HOME:-$HOME/.gradle}/caches/forge_gradle/assets', step)
        self.assertNotIn('FileNotFoundException', step)
