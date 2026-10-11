from pathlib import Path
import tempfile
import unittest

from release_dependencies import TARGETS, curseforge_relations, required_version, verify_release, verify_curseforge_metadata


class ReleaseDependencyTests(unittest.TestCase):
    def release(self):
        return {"tag_name": "v0.1.1", "draft": False, "prerelease": False,
                "assets": [{"name": f"packetweave-{target}-0.1.1.jar", "state": "uploaded", "size": 100} for target in TARGETS]}

    def test_every_loader_requires_exact_packetweave_project(self):
        for loader in ("fabric", "forge", "neoforge"):
            projects = curseforge_relations(loader)["projects"]
            packetweave = [p for p in projects if p["slug"] == "packetweave"]
            self.assertEqual([{"slug": "packetweave", "projectID": 1735096, "type": "requiredDependency"}], packetweave)
            self.assertTrue(all(p["type"] == "requiredDependency" for p in projects))
            self.assertEqual(loader == "fabric", any(p["slug"] == "fabric-api" for p in projects))
        with self.assertRaises(ValueError):
            curseforge_relations("unknown")

    def test_metadata_must_require_exact_id_and_slug(self):
        correct = {"slug": "packetweave", "projectID": 1735096, "type": "requiredDependency"}
        verify_curseforge_metadata({"relations": {"projects": [correct]}})
        invalid = ([], [dict(correct, projectID="42")], [dict(correct, projectID="1735096")],
                   [dict(correct, projectID=1735096.0)], [dict(correct, projectID=True)], [dict(correct, slug="unrelated")],
                   [dict(correct, type="optionalDependency")], [correct, correct],
                   [{"slug": "packetweave", "type": "requiredDependency"}])
        for projects in invalid:
            with self.subTest(projects=projects), self.assertRaisesRegex(ValueError, "publication is blocked"):
                verify_curseforge_metadata({"relations": {"projects": projects}})

    def test_unreleased_or_partial_dependency_prevents_screens_release(self):
        verify_release(self.release(), "0.1.1")
        for field, value in (("draft", True), ("prerelease", True), ("tag_name", "v0.1.0")):
            release = self.release()
            release[field] = value
            with self.assertRaises(ValueError):
                verify_release(release, "0.1.1")
        for target in TARGETS:
            release = self.release()
            release["assets"] = [a for a in release["assets"] if target not in a["name"]]
            with self.assertRaisesRegex(ValueError, "lacks required"):
                verify_release(release, "0.1.1")
        for state, size in (("new", 100), ("uploaded", 0)):
            release = self.release()
            release["assets"][0].update(state=state, size=size)
            with self.assertRaises(ValueError):
                verify_release(release, "0.1.1")

    def test_minimum_version_comes_from_one_valid_shared_property(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            props = root / "gradle.properties"
            props.write_text("packetweave_version = 0.1.1\n")
            self.assertEqual("0.1.1", required_version(root))
            for text in ("", "packetweave_version=../bad", "packetweave_version=0.1.1\npacketweave_version=0.1.2\n"):
                props.write_text(text)
                with self.assertRaises(ValueError):
                    required_version(root)

    def test_release_workflow_uses_required_relations_and_dependency_gate(self):
        workflow = (Path(__file__).resolve().parents[1] / ".github/workflows/release.yml").read_text()
        self.assertIn("python3 tools/release_dependencies.py relations --loader", workflow)
        self.assertIn("python3 tools/release_dependencies.py verify-release", workflow)
        self.assertNotIn("treating retry as successful", workflow)
        self.assertIn("curseforge-receipt-", workflow)
        self.assertIn('verify-curseforge-metadata <<< "$metadata"', workflow)
        self.assertNotIn("https://minecraft.curseforge.com/api/game/dependencies", workflow)


if __name__ == "__main__":
    unittest.main()
