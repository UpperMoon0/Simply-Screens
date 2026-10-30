from pathlib import Path
import hashlib
import json
import tempfile
import unittest
import zipfile
import re
from release_bundle import ALL_TARGETS, collect
from release_dependencies import TARGETS, REQUESTED_TARGETS, curseforge_relations, verify_release

ROOT = Path(__file__).resolve().parents[1]


class ReleaseBundleTests(unittest.TestCase):
    def make_bundle(self, root):
        source = root / 'artifacts'
        for target in ALL_TARGETS:
            directory = source / ('simply-screens-' + target)
            directory.mkdir(parents=True)
            with zipfile.ZipFile(directory / f'simply_screens-{target}-0.10.1.jar', 'w') as jar:
                jar.writestr('fixture', target)
        return source

    def test_complete_bundle_has_exact_ten_jars_and_hashes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = self.make_bundle(root)
            result = collect(source, root / 'out', '0.10.1')
            self.assertEqual(set(ALL_TARGETS), {entry['target'] for entry in result['artifacts']})
            self.assertEqual(10, len(result['artifacts']))
            for entry in result['artifacts']:
                self.assertEqual(entry['sha256'], hashlib.sha256((root / 'out' / entry['file']).read_bytes()).hexdigest())
            self.assertEqual(result, json.loads((root / 'out/manifest.json').read_text()))
            self.assertEqual(10, len((root / 'out/SHA256SUMS').read_text().splitlines()))

    def test_incomplete_duplicate_foreign_corrupt_and_stale_output_fail_closed(self):
        for mutation in ('missing', 'duplicate', 'foreign', 'corrupt', 'stale', 'symlink'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                source = self.make_bundle(root)
                jar = next(source.rglob('*.jar'))
                if mutation == 'missing': jar.unlink()
                if mutation == 'duplicate': (source / jar.name).write_bytes(jar.read_bytes())
                if mutation == 'foreign': jar.rename(jar.with_name('unrelated.jar'))
                if mutation == 'corrupt': jar.write_bytes(b'not a jar')
                if mutation == 'stale':
                    (root / 'out').mkdir()
                    (root / 'out/stale.jar').write_text('stale')
                if mutation == 'symlink':
                    saved = root / 'saved.jar'
                    jar.rename(saved)
                    jar.symlink_to(saved)
                with self.assertRaises((ValueError, zipfile.BadZipFile)):
                    collect(source, root / 'out', '0.10.1')

    def test_requested_dependencies_require_all_native_assets(self):
        for artifact, version in [('packetweave', '0.1.5'), ('openui-mc', '0.0.15')]:
            release = {'tag_name': 'v' + version, 'draft': False, 'prerelease': False,
                       'assets': [{'name': f'{artifact}-{target}-{version}.jar', 'state': 'uploaded', 'size': 10} for target in REQUESTED_TARGETS]}
            verify_release(release, version, REQUESTED_TARGETS, artifact)
            for index in range(len(REQUESTED_TARGETS)):
                partial = dict(release, assets=release['assets'][:index] + release['assets'][index + 1:])
                with self.assertRaises(ValueError): verify_release(partial, version, REQUESTED_TARGETS, artifact)

    def test_native_relations_and_complete_release_workflow(self):
        for target in ALL_TARGETS:
            loader, mc = target.split('-', 1)
            slugs = {item['slug'] for item in curseforge_relations(loader, mc)['projects']}
            self.assertEqual(target != 'forge-1.12.2', 'architectury-api' in slugs)
            self.assertTrue({'openui-mc', 'packetweave'} <= slugs)
            self.assertEqual(loader == 'fabric', 'fabric-api' in slugs)
        with self.assertRaises(ValueError): curseforge_relations('fabric', '1.12.2')
        serialized = (ROOT / '.github/workflows/release.yml').read_text()
        jobs = dict(re.findall(r'^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)', serialized, re.M | re.S))
        targets = re.search(r'target: \[([^]]+)\]', jobs['requested-build']).group(1)
        self.assertEqual(set(REQUESTED_TARGETS), set(targets.split(', ')))
        self.assertEqual(set(TARGETS), set(re.findall(r'project: ([\w.-]+)', jobs['build'])))
        published = re.findall(r"version: '([^']+)', platform: (\w+)", jobs['publish-curseforge'])
        self.assertEqual(set(ALL_TARGETS), {loader + '-' + mc for mc, loader in published})
        self.assertEqual(10, len(published))
        def needs(job):
            return set(re.search(r'needs: \[([^]]+)\]', jobs[job]).group(1).split(', '))
        self.assertEqual({'build', 'requested-build'}, needs('release-bundle'))
        self.assertTrue({'release-bundle', 'live-client'} <= needs('publish-curseforge'))
        self.assertTrue({'release-bundle', 'live-client', 'publish-curseforge'} <= needs('release'))
        requested = (ROOT / '.github/workflows/requested-generations.yml').read_text()
        # Keep native checkout refs aligned with the authoritative lockfile.
        # Exact checkout matching rejects a stale pin even if a new SHA appears
        # elsewhere in the workflow (for example, in a comment).
        lock = json.loads((ROOT / 'tools/requested-dependencies.lock.json').read_text())['dependencies']
        for key, repository in (
            ('packetweave', 'UpperMoon0/PacketWeave'),
            ('openui', 'UpperMoon0/OpenUI-MC'),
        ):
            self.assertEqual(repository, lock[key]['repository'])
            pin = lock[key]['commit']
            self.assertRegex(pin, r'^[0-9a-f]{40}$')
            checkout = re.compile(
                r'^\s*repository: ' + re.escape(repository) +
                r'\s*\n\s*ref: ([0-9a-f]{40})\s*$',
                re.M,
            )
            for label, workflow in (
                ('requested-generations', requested),
                ('release requested-build', jobs['requested-build']),
            ):
                with self.subTest(dependency=key, workflow=label):
                    refs = checkout.findall(workflow)
                    self.assertEqual([pin], refs, f'{label} must checkout the locked {key} commit')
        self.assertIn('verify_requested_jar.py', serialized)
        self.assertIn('verify-requested-releases', serialized)
        self.assertIn('name: verified-release-bundle', serialized)
        self.assertNotIn('files: artifacts/**/*.jar', serialized)


if __name__ == '__main__': unittest.main()
