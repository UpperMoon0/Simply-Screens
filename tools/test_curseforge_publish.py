"""Exercise the actual release shell against a fake curl, never the network."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
MOCK_CURL = r'''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
url = args[-1]
with open("requests.jsonl", "a") as log:
    log.write(json.dumps(args) + "\n")
assert args[args.index("--header") + 1] == "X-Api-Token: test-only"
if url == "https://minecraft.curseforge.com/api/game/versions":
    if os.environ.get("FAIL_VERSIONS"):
        sys.exit(22)
    print(json.dumps([{"name": name} for name in [os.environ["MINECRAFT_VERSION"], os.environ["LOADER_NAME"], os.environ["JAVA_VERSION_NAME"], "Client", "Server"]]))
elif url == "https://minecraft.curseforge.com/api/projects/1188741/upload-file":
    assert args[args.index("--request") + 1] == "POST"
    assert "--retry" not in args
    metadata = json.loads(args[args.index("--form-string") + 1].removeprefix("metadata="))
    pathlib.Path("submitted.json").write_text(json.dumps(metadata))
    pathlib.Path(args[args.index("--output") + 1]).write_text(os.environ.get("UPLOAD_BODY", '{"id":12345}'))
    print(os.environ.get("UPLOAD_STATUS", "200"), end="")
else:
    raise AssertionError("Unexpected endpoint: " + url)
'''


@unittest.skipUnless(shutil.which("bash") and shutil.which("jq"), "release shell requires bash and jq")
class CurseForgePublishTests(unittest.TestCase):
    def run_publish(self, **overrides):
        workflow = (ROOT / ".github/workflows/release.yml").read_text()
        step = workflow.split("      - name: Publish to CurseForge\n", 1)[1]
        script = step.split("        run: |\n", 1)[1].split("      - uses:", 1)[0]
        script = "\n".join(line[10:] for line in script.splitlines())
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "bin").mkdir()
            curl = root / "bin/curl"
            curl.write_text(MOCK_CURL)
            curl.chmod(0o755)
            (root / "tools").mkdir()
            shutil.copyfile(ROOT / "tools/release_dependencies.py", root / "tools/release_dependencies.py")
            (root / "changelog").mkdir()
            (root / "changelog/0.8.10.md").write_text("PacketWeave transport release")
            (root / "release.jar").write_bytes(b"fake jar, never uploaded")
            env = dict(os.environ, PATH=str(root / "bin") + os.pathsep + os.environ["PATH"],
                       CURSEFORGE_API_TOKEN="test-only", MOD_VERSION="0.8.10", JAR_PATH="release.jar",
                       PLATFORM="fabric", MINECRAFT_VERSION="1.20.1", LOADER_NAME="Fabric",
                       JAVA_VERSION_NAME="Java 17", GITHUB_SHA="test-source-sha")
            env.update(overrides)
            result = subprocess.run(["bash", "-c", script], cwd=root, env=env, text=True, capture_output=True)
            requests = [json.loads(line) for line in (root / "requests.jsonl").read_text().splitlines()]
            receipts = [json.loads(p.read_text()) for p in root.glob("curseforge-receipts/*.json")]
            submitted = root / "submitted.json"
            return result, requests, receipts, json.loads(submitted.read_text()) if submitted.exists() else None

    def test_all_five_targets_upload_exact_required_relation_and_save_receipt(self):
        for platform, version, loader, java in (("fabric", "1.20.1", "Fabric", "Java 17"),
                ("forge", "1.20.1", "Forge", "Java 17"), ("fabric", "1.21.1", "Fabric", "Java 21"),
                ("neoforge", "1.21.1", "NeoForge", "Java 21"), ("neoforge", "26.1.2", "NeoForge", "Java 25")):
            with self.subTest(platform=platform, version=version):
                result, requests, receipts, metadata = self.run_publish(PLATFORM=platform, MINECRAFT_VERSION=version,
                                                                         LOADER_NAME=loader, JAVA_VERSION_NAME=java)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(2, len(requests))
                expected = {"slug": "packetweave", "projectID": 1735096, "type": "requiredDependency"}
                self.assertIn(expected, metadata["relations"]["projects"])
                packetweave = next(p for p in metadata["relations"]["projects"] if p["slug"] == "packetweave")
                self.assertIs(type(packetweave["projectID"]), int)
                self.assertEqual(1, len(receipts))
                self.assertEqual(metadata["relations"], receipts[0]["relations"])
                self.assertEqual(12345, receipts[0]["file_id"])

    def test_rejected_or_unconfirmed_upload_fails_without_receipt_or_retry(self):
        for status, body in (("400", '{"error":"Invalid relation"}'), ("401", '{}'), ("404", '{}'),
                             ("500", '{}'), ("200", '{}'), ("200", '{"id":0}'), ("200", 'not json')):
            with self.subTest(status=status, body=body):
                result, requests, receipts, _ = self.run_publish(UPLOAD_STATUS=status, UPLOAD_BODY=body)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(2, len(requests))
                self.assertEqual([], receipts)

    def test_version_endpoint_failure_prevents_upload(self):
        result, requests, receipts, metadata = self.run_publish(FAIL_VERSIONS="1")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(1, len(requests))
        self.assertEqual([], receipts)
        self.assertIsNone(metadata)


if __name__ == "__main__":
    unittest.main()
