import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock

from visual_cases import TARGETS, FACES, SAMPLES, sample_count, cases, expected_outcome, variants, NF26_TARGET, NEAR_REFERENCE_FACES
from visual_test import atomic_json, checkout_lock, health, scope_gradle, verify_receipts, wait_until, verify_nf26_control, restore_common_control_models, restore_nf26_control_models, NF26_WORLD_MODELS, COMMON_WORLD_MODELS, COMMON_WORLD_MODEL_PATHS, ROOT
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

    def test_production_build_is_target_scoped_before_gradle_starts(self):
        workflow = (ROOT / ".github/workflows/screen-visual.yml").read_text()
        self.assertIn("python tools/verify_production_build.py --target '${{ matrix.target }}'", workflow)
        self.assertNotIn("./gradlew :${{ matrix.target }}:build", workflow)

        verifier = (ROOT / "tools/verify_production_build.py").read_text()
        scope = verifier.index("scope_gradle(stage, target)")
        build = verifier.index("command = gradle_command(stage, target)")
        self.assertLess(scope, build)
        self.assertIn('"--module", str(stage / target)', verifier)

    def test_depth_fixture_isolates_faces_and_keeps_explicit_cross_chunk_cases(self):
        server = (ROOT / "tools/visual/VisualServer.java").read_text()
        self.assertIn("BlockPos anchor = fixtureAnchor(facing, crossChunk || anchorUnloaded);", server)
        self.assertIn("lane * 32 + (crossChunk ? 0 : 8)", server)
        # Six deterministic face lanes are separated by two chunks. A normal size-8
        # footprint starts at local x/z 8, so +/-7 cells remain inside that lane's
        # chunk instead of depending on a neighboring chunk rebuild.
        anchors = [lane * 32 + 8 for lane in range(6)]
        self.assertEqual(6, len(set(anchors)))
        self.assertTrue(all(anchor % 16 == 8 for anchor in anchors))
        self.assertTrue(8 - 7 >= 0 and 8 + 7 <= 15)
        self.assertEqual(2, sum(1 for c in cases() if c.get("crossChunk")))
        anchor_unloaded = [c for c in cases() if c.get("anchorUnloaded")]
        self.assertEqual(["NORTH-anchor-unloaded"], [c["id"] for c in anchor_unloaded])
        self.assertEqual(
            (64, 76, 60, 64),
            (anchor_unloaded[0]["size"], anchor_unloaded[0]["distance"],
             anchor_unloaded[0]["angle"], anchor_unloaded[0]["maxPixelDistance"]),
        )
        # Match modern vanilla tracked-view behavior and legacy 1.20.1 client
        # storage. The final camera must evict anchor chunk (0,0) at radius 3
        # while retaining real child chunks near the viewed screen edge.
        import math
        center_x, center_z = -31.0, 8.0
        angle = math.radians(anchor_unloaded[0]["angle"])
        distance = anchor_unloaded[0]["distance"]
        eye_x = center_x - distance * math.sin(angle)
        eye_z = center_z - distance * math.cos(angle)
        player_chunk = (math.floor(eye_x / 16), math.floor(eye_z / 16))
        self.assertEqual((-7, -2), player_chunk)

        def tracked(chunk):
            dx = max(0, abs(chunk[0] - player_chunk[0]) - 2)
            dz = max(0, abs(chunk[1] - player_chunk[1]) - 2)
            return dx * dx + dz * dz < 3 * 3

        self.assertFalse(tracked((0, 0)))
        self.assertTrue(all(tracked((x, 0)) for x in (-3, -4)))
        legacy_client_radius = 3 + 3
        legacy_cached = lambda chunk: (
            abs(chunk[0] - player_chunk[0]) <= legacy_client_radius
            and abs(chunk[1] - player_chunk[1]) <= legacy_client_radius
        )
        self.assertFalse(legacy_cached((0, 0)))
        self.assertTrue(all(legacy_cached((x, 0)) for x in (-1, -2, -3, -4)))
        self.assertEqual("NORTH-anchor-unloaded", cases()[-1]["id"])
        anchor_loaded = [c for c in cases() if c.get("anchorLoadedFallback")]
        self.assertEqual(["NORTH-anchor-loaded-fallback"], [c["id"] for c in anchor_loaded])
        self.assertEqual(
            (8, 32, 0),
            (anchor_loaded[0]["size"], anchor_loaded[0]["distance"], anchor_loaded[0]["angle"]),
        )

    def test_anchor_unloaded_fixture_excludes_anchor_and_requires_child_rendering(self):
        server = (ROOT / "tools/visual/VisualServer.java").read_text()
        client = (ROOT / "tools/visual/VisualClient.java").read_text()
        pixels = (ROOT / "tools/visual_pixels.py").read_text()
        harness = (ROOT / "tools/visual_test.py").read_text()
        self.assertIn("server.getPlayerList().setViewDistance(16);", server)
        self.assertIn("server.getPlayerList().setViewDistance(3);", server)
        self.assertIn('DIR.resolve("anchor-sync-ack.txt")', server)
        self.assertIn('DIR.resolve("anchor-radius-reduced.txt")', server)
        self.assertNotIn("__FORGET_ANCHOR__", server)
        self.assertNotIn("ClientboundForgetLevelChunkPacket", harness)
        self.assertIn("fixtureAnchor(facing, crossChunk || anchorUnloaded)", server)
        self.assertIn('Path ack = DIR.resolve("anchor-sync-ack.txt")', client)
        self.assertIn("Files.writeString(ack, current", client)
        self.assertIn('"pre-unload anchor chunk to be loaded"', client)
        self.assertIn('"anchor chunk to unload after server view-distance reduction"', client)
        self.assertIn('"at least one loaded child screen cell after anchor unload"', client)
        self.assertIn("return __CHUNK_LOADED__;", client)
        self.assertIn("owner.equals(sceneAnchor())", client)
        self.assertIn('frame.addProperty("anchorChunkLoaded", chunkLoaded(mc, sceneAnchor()))', client)
        self.assertIn('frame.add("loadedCells", loadedScreenCells(mc))', client)
        self.assertIn('frame.get("anchorChunkLoaded") is not False', harness)
        self.assertIn("if (anchorUnloadedScenario()) return true;", client)
        self.assertIn('frame.get("loadedCells", [])', pixels)
        self.assertIn('frame.get("maxPixelDistance")', pixels)
        self.assertIn('np.linalg.norm(hit - eye, axis=-1)', pixels)
        self.assertIn("blockEntity.getBlockPos().getX()", harness)
        self.assertIn("state.blockPos.getX()", harness)
        self.assertIn("VisualClient.skipAnchorOwner(blockEntity.isAnchor())", harness)
        self.assertIn("state.anchorOffsetX == 0", harness)
        self.assertIn("anchorLoadedFallbackScenario()", client)
        self.assertIn('frame.addProperty("anchorEntityPresent"', client)
        self.assertIn('case.get("anchorLoadedFallback")', harness)

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
        self.assertIn("def remove_runtime_when_quiet", harness)
        self.assertIn("remove_runtime_when_quiet(runtime)", harness)
        self.assertIn("sceneSynchronized(mc, image)", source)
        self.assertIn("private static BlockPos sceneAnchor()", source)
        self.assertIn('scene.getAsJsonArray("anchor")', source)
        self.assertNotIn("anchorX == 0 && anchorY == 128 && anchorZ == 0", source)
        self.assertNotIn("new BlockPos(0,128,0)", source.replace(" ", ""))
        self.assertIn("sceneSynchronized(mc, image)", source)
        self.assertIn("chunkLoaded(mc, anchor)", source)
        self.assertIn("mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);", source)
        self.assertIn('frame.addProperty("clouds", mc.options.cloudStatus().get().name());', source)
        self.assertIn('if frame.get("clouds") != "OFF":', harness)
        server = (ROOT / "tools/visual/VisualServer.java").read_text()
        self.assertIn('ready.add("anchor"', server)
        self.assertIn("catch (NoSuchFileException transientSceneGap)", source)
        self.assertIn("cameraMatches(cameraPosition, cameraYaw, cameraPitch", source)
        self.assertIn("cameraStable(cameraPosition, cameraYaw, cameraPitch)", source)
        self.assertIn("WorldRenderEvents.END", harness)
        self.assertIn("Stage.AFTER_LEVEL", harness)
        self.assertIn("RenderLevelStageEvent.AfterLevel", harness)
        self.assertGreaterEqual(source.count("resetStability();"), 5)

    def test_unique_manifest_and_full_matrix(self):
        self.assertEqual(5, len(TARGETS))
        self.assertEqual(113, len(cases()))
        self.assertEqual(4, SAMPLES)
        regular = next(c for c in cases() if not c.get("reload"))
        reload_case = next(c for c in cases() if c.get("reload"))
        self.assertEqual(4, sample_count(regular))
        self.assertEqual(8, sample_count(reload_case))
        far_small_oblique = [c for c in cases() if c["size"] == 2 and c["distance"] == 160 and c["angle"] != 0]
        self.assertEqual(len(FACES), len(far_small_oblique))
        self.assertTrue(all(c["angle"] == 30 for c in far_small_oblique))
        cross_chunk = [c for c in cases() if c.get("crossChunk")]
        self.assertEqual({"NORTH-cross-chunk", "UP-cross-chunk"}, {c["id"] for c in cross_chunk})
        self.assertTrue(all(not c["occluded"] for c in cross_chunk))
        alpha = [c for c in cases() if c.get("alpha")]
        self.assertEqual(["NORTH-alpha"], [c["id"] for c in alpha])
        self.assertTrue(all(c not in cases("plain") and c not in cases("see-through") for c in alpha))
        self.assertTrue(all(variants(target) == ("fixed", "plain", "see-through") for target in TARGETS))
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

    def test_plain_must_reproduce_but_near_reference_must_pass(self):
        result = {c["id"]: dict(status="pass") for c in cases("plain")}
        with self.assertRaisesRegex(ValueError, "INCONCLUSIVE"):
            expected_outcome("plain", result)
        far = next(c["id"] for c in cases("plain") if c["distance"] == 160)
        result[far]["status"] = "pixel-failure"
        expected_outcome("plain", result)
        result[far]["status"] = "crash"
        with self.assertRaisesRegex(ValueError, "invalid"):
            expected_outcome("plain", result)
        result[far]["status"] = "pixel-failure"
        # Horizontal faces can already z-fight in the deliberately broken plain
        # renderer; cardinal near-reference cases remain the clean comparison.
        horizontal_near = next(c["id"] for c in cases("plain")
                               if c["distance"] == 8 and c["facing"] == "UP" and c["angle"] == 0)
        result[horizontal_near]["status"] = "pixel-failure"
        expected_outcome("plain", result)
        result[horizontal_near]["status"] = "pass"

        cardinal_near = next(c["id"] for c in cases("plain")
                             if c["distance"] == 8 and c["angle"] == 0
                             and c["facing"] in NEAR_REFERENCE_FACES)
        result[cardinal_near]["status"] = "pixel-failure"
        with self.assertRaisesRegex(ValueError, "near reference"):
            expected_outcome("plain", result)

    def test_negative_controls_reconstruct_coplanar_world_models(self):
        with tempfile.TemporaryDirectory() as directory:
            stage = Path(directory)
            for index, common_model in enumerate(COMMON_WORLD_MODEL_PATHS):
                model = {
                    "textures": {"front": "#front", "marker": f"case-{index}"},
                    "elements": [
                        {"from": [0, 0, 0], "to": [16, 16, 16], "faces": {"south": {"texture": "#front"}}},
                        {"from": [0, 0, 1], "to": [16, 16, 1.001], "faces": {"north": {"texture": "#front"}}},
                    ],
                }
                (stage / common_model).parent.mkdir(parents=True, exist_ok=True)
                (stage / common_model).write_text(json.dumps(model), encoding="utf-8")
            for local_model in NF26_WORLD_MODELS:
                (stage / local_model).parent.mkdir(parents=True, exist_ok=True)
                (stage / local_model).write_text("{}", encoding="utf-8")

            restore_common_control_models(stage)
            restore_nf26_control_models(stage)

            for model_path in (*COMMON_WORLD_MODEL_PATHS, *NF26_WORLD_MODELS):
                model = json.loads((stage / model_path).read_text(encoding="utf-8"))
                self.assertEqual(1, len(model["elements"]))
                body = model["elements"][0]
                self.assertEqual([0, 0, 0], body["from"])
                self.assertEqual([16, 16, 16], body["to"])
                self.assertEqual("#front", body["faces"]["north"]["texture"])

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

    def test_alpha_fixture_requires_transparency_and_half_alpha_blending(self):
        import math
        from PIL import Image, ImageDraw
        from visual_pixels import measure
        frame = dict(camera=[0,0,-32], center=[0,0,0], normal=[0,0,-1], right=[-1,0,0], up=[0,1,0],
                     cameraYaw=0, cameraPitch=0, fov=70, size=8,
                     occluders=[], occluded=False, alpha=True)
        focal = 720/(2*math.tan(math.radians(35)))
        half = focal*4/32
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/"alpha.png"
            image = Image.new("RGB", (960,720), (0,0,0))
            draw = ImageDraw.Draw(image)
            def band(lo, hi, color):
                x0, x1 = half*lo/0.5, half*hi/0.5
                draw.rectangle((480-x1,360-half,480-x0,360+half), fill=color)
                draw.rectangle((480+x0,360-half,480+x1,360+half), fill=color)
            band(0.0, 0.15, (240,24,240))
            band(0.20, 0.32, (120,12,120))
            image.save(path)
            result = measure(path, frame)
            self.assertEqual("pass", result["status"])
            self.assertGreater(result["alpha_blend_fraction"], 0.20)
            self.assertLess(result["alpha_blend_fraction"], 0.85)

            # sRGB-style framebuffer blending can make 50% source alpha display
            # around 75% of the opaque channel value. That is still real blending.
            band(0.20, 0.32, (178,18,178))
            image.save(path)
            srgb = measure(path, frame)
            self.assertEqual("pass", srgb["status"])
            self.assertGreater(srgb["alpha_blend_fraction"], 0.70)

            # Ignored alpha makes the semi-transparent band indistinguishable
            # from opaque and must still fail the semantic contract.
            band(0.20, 0.32, (240,24,240))
            image.save(path)
            self.assertEqual("pixel-failure", measure(path, frame)["status"])

            image = Image.new("RGB", (960,720), (0,0,0))
            draw = ImageDraw.Draw(image)
            band(0.0, 0.15, (240,24,240))
            band(0.20, 0.32, (120,12,120))
            band(0.37, 0.46, (240,24,240))
            image.save(path)
            self.assertEqual("pixel-failure", measure(path, frame)["status"])

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
