import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock

from visual_cases import TARGETS, FACES, SAMPLES, sample_count, cases, expected_outcome, variants, classify_nf26, NF26_TARGET
from visual_test import atomic_json, checkout_lock, health, scope_gradle, verify_receipts, wait_until, verify_nf26_control, ROOT
from compiled_render_contract import verify_dump


class EvidenceTests(unittest.TestCase):
    def test_visual_snapshot_scopes_gradle_to_selected_target(self):
        expected = {
            "fabric-1.20.1": {"common", "common-1.20.1", "fabric-1.20.1"},
            "forge-1.20.1": {"common", "common-1.20.1", "forge-1.20.1"},
            "fabric-1.21.1": {"common", "common-1.21.1", "fabric-1.21.1"},
            "neoforge-1.21.1": {"common", "common-1.21.1", "neoforge-1.21.1"},
            "neoforge-26.1.2": {"neoforge-26.1.2"},
        }
        all_projects = sorted(set().union(*expected.values()))
        for target, wanted in expected.items():
            with self.subTest(target=target), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / "settings.gradle").write_text("\n".join(f"include '{p}'" for p in all_projects) + "\n")
                (root / "gradle.properties").write_text("minecraft_version = 1.21.1\n")
                scope_gradle(root, target)
                found = {line.split("'")[1] for line in (root / "settings.gradle").read_text().splitlines() if line.startswith("include '")}
                self.assertEqual(wanted, found)
                root_mc = (root / "gradle.properties").read_text().strip()
                self.assertEqual("minecraft_version = 1.20.1" if target.endswith("1.20.1") else "minecraft_version = 1.21.1", root_mc)

    def test_visual_client_captures_only_after_stable_completed_render_frames(self):
        source = (ROOT / "tools/visual/VisualClient.java").read_text()
        harness = (ROOT / "tools/visual_test.py").read_text()
        self.assertIn("REQUIRED_STABLE_FRAMES = 3", source)
        self.assertIn("public static void beginRenderFrame()", source)
        self.assertIn("public static void afterRender(Minecraft mc)", source)
        self.assertIn("public static void submittedTile(Direction facing", source)
        self.assertIn("public static void submittedScreen(Direction facing", source)
        self.assertIn("frameMatchesCurrentFixture()", source)
        self.assertIn("frameUnexpectedSubmission", source)
        self.assertIn("!frameTileOwners.isEmpty()", source)
        self.assertIn("submittedTile(facing", harness)
        self.assertIn("submittedScreen(facing", harness)
        self.assertIn("REQUIRED_FIRST_SAMPLE_STABLE_FRAMES = 5", source)
        self.assertIn("stableFrames < requiredStableFrames", source)
        self.assertIn("sceneSynchronized(mc, image)", source)
        self.assertIn("catch (NoSuchFileException transientSceneGap)", source)
        self.assertIn("cameraMatches(cameraPosition, cameraYaw, cameraPitch", source)
        self.assertIn("cameraStable(cameraPosition, cameraYaw, cameraPitch)", source)
        self.assertIn("WorldRenderEvents.END", harness)
        self.assertIn("Stage.AFTER_LEVEL", harness)
        self.assertIn("RenderLevelStageEvent.AfterLevel", harness)
        self.assertGreaterEqual(source.count("resetStability();"), 5)

    def test_unique_manifest_and_full_matrix(self):
        self.assertEqual(5, len(TARGETS))
        self.assertEqual(108, len(cases()))
        self.assertEqual(4, SAMPLES)
        regular = next(c for c in cases() if not c.get("reload"))
        reload_case = next(c for c in cases() if c.get("reload"))
        self.assertEqual(4, sample_count(regular))
        self.assertEqual(8, sample_count(reload_case))
        far_small_oblique = [c for c in cases() if c["size"] == 2 and c["distance"] == 160 and c["angle"] != 0]
        self.assertEqual(len(FACES), len(far_small_oblique))
        self.assertTrue(all(c["angle"] == 30 for c in far_small_oblique))
        self.assertEqual(("fixed", "original", "single-plain", "tiled-offset", "see-through"), variants(NF26_TARGET))
        for target in TARGETS:
            for variant in variants(target):
                self.assertEqual(len(cases(variant)), len({c["id"] for c in cases(variant)}))

    def test_fixed_requires_every_frame_and_every_case(self):
        good = {c["id"]: dict(status="pass", occlusion_errors=0) for c in cases()}
        expected_outcome("fixed", good)
        good[next(iter(good))]["status"] = "pixel-failure"
        with self.assertRaisesRegex(ValueError, "fixed renderer"):
            expected_outcome("fixed", good)
        good.pop(next(iter(good)))
        with self.assertRaisesRegex(ValueError, "missing"):
            expected_outcome("fixed", good)

    def test_original_must_reproduce_but_near_reference_must_pass(self):
        result = {c["id"]: dict(status="pass") for c in cases("original")}
        with self.assertRaisesRegex(ValueError, "INCONCLUSIVE"):
            expected_outcome("original", result)
        far = next(c["id"] for c in cases("original") if c["distance"] == 160)
        result[far]["status"] = "pixel-failure"
        expected_outcome("original", result)
        result[far]["status"] = "crash"
        with self.assertRaisesRegex(ValueError, "invalid"):
            expected_outcome("original", result)
        result[far]["status"] = "pixel-failure"
        near = next(c["id"] for c in cases("original") if c["distance"] == 8)
        result[near]["status"] = "pixel-failure"
        with self.assertRaisesRegex(ValueError, "near reference"):
            expected_outcome("original", result)

    def test_nf26_2x2_classification(self):
        def result(name, far_failure):
            data = {c["id"]: dict(status="pass") for c in cases(name)}
            if far_failure:
                far = next(c["id"] for c in cases(name) if c["distance"] == 160)
                data[far]["status"] = "pixel-failure"
            return data
        base = {
            "fixed": result("fixed", False),
            "original": result("original", True),
            "single-plain": result("single-plain", True),
            "tiled-offset": result("tiled-offset", False),
        }
        self.assertTrue(classify_nf26(base).startswith("depth-state:"))
        base["single-plain"] = result("single-plain", False)
        base["tiled-offset"] = result("tiled-offset", True)
        self.assertTrue(classify_nf26(base).startswith("topology:"))
        base["single-plain"] = result("single-plain", True)
        self.assertTrue(classify_nf26(base).startswith("combined:"))
        base["single-plain"] = result("single-plain", False)
        base["tiled-offset"] = result("tiled-offset", False)
        self.assertTrue(classify_nf26(base).startswith("non-unique:"))

    def test_nf26_original_control_fixture_is_hash_locked(self):
        verify_nf26_control(ROOT)

    def test_failure_and_server_exit_override_available_frames(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            server = Mock(poll=Mock(return_value=None))
            client = Mock(poll=Mock(return_value=None))
            (root / "client-fail.txt").write_text("failed assertion")
            with self.assertRaisesRegex(RuntimeError, "failed assertion"):
                wait_until(lambda: True, root, server, client, 0)
            (root / "client-fail.txt").unlink()
            server.poll.return_value = 0
            with self.assertRaisesRegex(RuntimeError, "server exited"):
                health(root, server, client)

    def test_receipts_reject_stale_dirty_missing_duplicate_and_missing_controls(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for target in TARGETS:
                path = root / target / "result.json"
                path.parent.mkdir()
                payload = dict(target=target, head="abc", dirty=False, status="pass",
                               variants={v:"pass" for v in variants(target)})
                if target == NF26_TARGET:
                    payload["nf26_diagnosis"] = "depth-state: test"
                atomic_json(path, payload)
            verify_receipts(root, "abc")
            path = root / TARGETS[0] / "result.json"
            original = json.loads(path.read_text())
            for changes in ({"head":"stale"}, {"dirty":True}, {"status":"compile-only"}, {"variants":{}}):
                atomic_json(path, original | changes)
                with self.assertRaises(ValueError): verify_receipts(root,"abc")
            atomic_json(path, original)
            duplicate = root / "duplicate/result.json"
            duplicate.parent.mkdir(); atomic_json(duplicate, original)
            with self.assertRaisesRegex(ValueError, "duplicate"): verify_receipts(root,"abc")
            duplicate.unlink(); path.unlink()
            with self.assertRaisesRegex(ValueError, "missing"): verify_receipts(root,"abc")

    def test_checkout_lock_excludes_and_releases(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with checkout_lock(root):
                with self.assertRaises(RuntimeError):
                    with checkout_lock(root): self.fail("acquired twice")
            with checkout_lock(root): pass


class CompiledTests(unittest.TestCase):
    def dump(self, modern=False):
        return ("  private static final float BASE_OFFSET = 0.501f;\n"
                + ("  public void submit(State state);\n" if modern else "  private void renderTextureQuad(Texture texture);\n")
                + "    Code:\n      0: invokestatic #1 // Method RenderType.textPolygonOffset:(LTexture;)LRenderType;\n"
                + ("      3: invokeinterface #2 // InterfaceMethod Collector.submitCustomGeometry:(LRenderType;)V\n" if modern
                   else "      3: invokeinterface #2 // InterfaceMethod MultiBufferSource.getBuffer:(LRenderType;)LConsumer;\n"))

    def test_each_version_rejects_plain_see_through_large_and_discarded_call(self):
        for version in ("1.20.1", "1.21.1", "26.1.2"):
            modern = version == "26.1.2"
            good = self.dump(modern)
            verify_dump(good, modern)
            for broken in (good.replace(".textPolygonOffset:", ".text:"),
                           good.replace(".textPolygonOffset:", ".textSeeThrough:"),
                           good.replace("0.501f", "0.550f"),
                           good.replace("      3:", "      2: pop\n      3:")):
                with self.subTest(version=version, mutation=broken), self.assertRaises(ValueError):
                    verify_dump(broken, modern)


class PixelTests(unittest.TestCase):
    def test_foreground_occlusion_control(self):
        import math
        from PIL import Image, ImageDraw
        from visual_pixels import measure
        frame = dict(camera=[0,0,-32], center=[0,0,0], normal=[0,0,-1], right=[-1,0,0], up=[0,1,0],
                     cameraYaw=0, cameraPitch=0, fov=70, size=8,
                     occluders=[[0,-4,-1.5,4,4,-0.5]], occluded=True)
        focal = 720/(2*math.tan(math.radians(35)))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/"frame.png"
            image = Image.new("RGB", (960,720), (0,0,0))
            draw = ImageDraw.Draw(image)
            half = focal*4/32
            image_box = (480-half,360-half,480+half,360+half)
            draw.rectangle(image_box, fill=(240,24,240))
            front = focal*4/30.5
            draw.rectangle((480-front,360-front,480,360+front), fill=(65,63,78))
            image.save(path)
            self.assertEqual("pass",measure(path,frame)["status"])
            draw.rectangle(image_box, fill=(240,24,240)); image.save(path)
            self.assertGreater(measure(path,frame)["occlusion_errors"], 0.9)

    def test_missing_image_and_single_corrupt_frame_fail(self):
        import math
        from PIL import Image, ImageDraw
        from visual_pixels import measure
        frame = dict(camera=[0,0,-32], center=[0,0,0], normal=[0,0,-1], right=[-1,0,0], up=[0,1,0],
                     cameraYaw=0, cameraPitch=0, fov=70, size=8, occluders=[], occluded=False)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "frame.png"
            image = Image.new("RGB",(960,720),(0,0,0))
            half = 720/(2*math.tan(math.radians(35)))*4/32
            draw = ImageDraw.Draw(image)
            draw.rectangle((480-half,360-half,480+half,360+half), fill=(240,24,240))
            image.save(path)
            self.assertEqual("pass", measure(path,frame)["status"])
            draw.rectangle((470,300,490,420), fill=(0,0,0)); image.save(path)
            self.assertEqual("pixel-failure", measure(path,frame)["status"])
            Image.new("RGB",(960,720),(0,0,0)).save(path)
            self.assertEqual("pixel-failure", measure(path,frame)["status"])
            Image.new("RGB",(960,720),(240,24,240)).save(path)
            with self.assertRaisesRegex(ValueError,"silhouette"): measure(path,frame)


if __name__ == "__main__": unittest.main()
