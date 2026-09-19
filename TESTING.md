# Testing Simply Screens

Simply Screens uses layered verification. A passing unit suite alone is not enough for renderer, loader, networking, or packaging changes.

## 1. Source and harness contracts

Run:

```bash
python3 -m unittest discover -s tools -p 'test_*.py' -v
python3 tools/render_contract.py
```

`render_contract.py` is the regression gate for the world-space image layer. It verifies every supported renderer implementation:

- keeps the physical image plane at `BASE_OFFSET = 0.501f` so it remains visually flush with the screen block;
- uses a text-compatible polygon-offset render path instead of normal text depth state (vanilla `textPolygonOffset` on 1.20.1, custom equivalent render state/pipeline on 1.21.1 and 26.1.2);
- does not use see-through rendering, which would allow images to draw through real occluders;
- remains paired with the current full block body plus the authored front texture recessed by 1/16 block, avoiding a coplanar model face.

The verifier has negative self-tests so a broken verifier cannot silently bless the exact regressions it is meant to catch. The live-client Python harness is also self-tested for target coverage and deterministic client/server fixtures.

## 2. JVM unit tests

Run:

```bash
./gradlew testAllVersions --no-daemon
```

This covers shared logic plus version-specific renderer/cache/UI/network behavior. It also compile-checks the Minecraft-specific render APIs used by all supported source sets. After compilation run `python3 tools/compiled_render_contract.py`. It inspects the actual renderer bytecode with the JDK's `javap`: the image submission must consume the version-appropriate polygon-offset text path, plain/see-through calls are rejected, and the compiled physical offset remains `0.501f`. Comments cannot satisfy this check.

## 3. Real client/server integration

Run one target:

```bash
python3 tools/live_join_test.py --target neoforge-1.21.1
```

Supported targets are Fabric and Forge 1.20.1, Fabric and NeoForge 1.21.1, and NeoForge 26.1.2. The harness builds the production jar, checks its packaged shape, starts a dedicated server, launches a graphical client under a real display/Xvfb, joins it, exercises the UI smoke path, and requires the explicit pass marker.

PR CI runs this matrix for runtime-affecting changes. CI checks out the exact PR head so a green result cannot refer only to GitHub's synthetic merge commit.

## Renderer regression protocol

Issue #8 is a depth-ordering regression. Its automated contract deliberately checks the actual invariant rather than asserting a larger geometric offset. For final visual verification of renderer changes, use a screen with a high-contrast image and check all of these conditions:

- near, ~32, ~64, and 128+ block camera distances;
- frontal and steep oblique angles;
- small and multi-block screens;
- horizontal and vertical facings;
- a solid occluder placed immediately in front of part of the screen.

Pass means the image has no black/flickering/z-fighting patches, remains visually flush from the side, has no distance-dependent popping, and remains hidden by real foreground geometry.

Exact screenshot equality is not used: raster output varies across graphics implementations. The targeted framebuffer gate below classifies colors in independently projected geometric regions instead.

## Merge discipline

For renderer/runtime changes, merge only when the source/harness contract job, `testAllVersions`, compiled render contract, all five live-client jobs, and **Required screen visual evidence** are green on the exact final PR head. Review and merge are separate actions. Upload CI logs on failure rather than relying on console truncation.

## 4. Automated depth-conflict reproduction

```sh
python3 -m pip install -r tools/visual/requirements.txt
python3 tools/visual_test.py --target fabric-1.21.1
```

Install the pinned OpenUI dependencies as in CI first. Java 21 runs Gradle; the build resolves the version-specific Java toolchains. Linux needs Xvfb and Mesa, Windows needs a graphical desktop. The harness uses the existing dedicated-server and graphical-client launch targets. It snapshots the checkout under `build/screen-visual-work` and injects test drivers only there. Production sources/jars never contain these drivers or runtime switches to broken render types. CI builds and checks the production jar before instrumentation.

The server imports deterministic opaque-magenta and alpha-stripe PNGs with the production image manager, creates actual screen blocks and block entities, updates their image/anchor data, and teleports a spectator to the fixture. The client receives the ordinary screen updates and image download. It requires the intended image UUID, camera position, and actual renderer submission before taking samples. Client-side fake block state is not a substitute for server setup.

`tools/visual_cases.py` is the sole scenario manifest:

- Every supported loader/version target; all six facings.
- 2×2 and 8×8 screens at 8, 32, 64 and 160 blocks; frontal and 60-degree oblique views.
- A red foreground occluder covering half the screen, separately for every facing.
- A resource/shader reload case for every facing, with four samples before and four after successful reload.
- A transparent/semi-transparent alpha case that checks a fully transparent stripe, a 50% alpha stripe, and an opaque reference in the same frame.
- Four samples per normal scene, one for each unique sub-degree raster alignment. Each camera offset is armed first and captured only after a subsequent real renderer submission; there is no fixed warm-up or inter-sample sleep. Every sample must pass.

The oracle projects the independently specified screen plane from recorded fixture geometry. Opaque cases check magenta in exposed screen interiors and image leakage in covered interiors; the alpha case normalizes the 50%-alpha band between the same frame's transparent backing and opaque reference. This preserves the semantic check across linear/sRGB framebuffer differences while still rejecting ignored or discarded alpha. It excludes silhouette edges and rejects insufficient pixel coverage or image color outside the expected silhouette. It never finds the expected region by searching for the image color itself. The error budget is 0.5% per region per sample; the worst sample determines the scene result. A missing texture, missing screen, stalled capture, crash or stale frame is not a successful visual check.

Each target runs isolated variants, reusing compiled outputs but starting fresh worlds/processes:

1. **Fixed**: all 111 scenarios and every sample pass, including the alpha/blending scene.
2. **Plain text negative control**: for 1.20.1/1.21.1 targets, the renderer is changed back to plain text depth state and the disposable snapshot also restores the original coplanar authored front model so the old conflict remains reproducible. Every near reference must pass, and at least one 64/160-block scenario must show pixel corruption.
3. **See-through negative control**: every foreground case must show substantial occlusion failure. A crash cannot satisfy this control.

NeoForge 26.1.2 uses a hash-locked **original** control: the exact three-file renderer/state/client implementation from `main` at `1ec1058a8026294df8bd34ed21e6852d0244f26c`, with only the visual submission counter injected into the disposable snapshot. The common **plain** variant exercises the current single-quad ownership path with plain depth state and restored coplanar control models. Together these controls distinguish the actual pre-fix renderer failure from the current topology with depth bias removed without maintaining obsolete diagnostic-only variants.

The enlarged-offset mutation is rejected by the fast source/compiled contract, not by changing geometry to make the pixel reproduction easier. Negative controls mutate only the disposable snapshot. No assertion failure is retried into a pass.

`--compile-only` compiles every required instrumented/control variant for the selected target; `--probe` runs a diagnostic subset covering the north-facing distance sweep, other facings, small far screens, occlusion and reload. Both deliberately produce non-passing receipts and cannot satisfy the merge gate.

## Evidence and limits

`build/screen-visual-evidence/<target>/<run-id>/` retains frame PNGs, camera/scene metadata, graphics vendor/renderer/version, per-frame error measurements, server/client logs, crash reports and a `result.json` receipt. Receipts bind the run to the source commit and a snapshot digest; dirty local runs cannot certify an exact commit. The aggregate gate rejects missing/duplicate targets, stale/dirty receipts, incomplete controls and failed runs. Fresh unique directories and a checkout lock prevent old images/results from supplying a pass.

CI uses a software graphics backend and records its identity; that backend is not a guarantee about every physical GPU, shader pack, or optional renderer. The suite must first demonstrate the old-renderer failure on a repeatable configuration before it can certify the fix there. If the original bug cannot be reproduced, preserve the evidence and improve the fixture/backend—do not loosen thresholds or relabel startup success as visual proof. Third-party renderer and shader-pack matrices remain separate extensions; they are not claimed by this gate.

The five jobs share one OpenUI dependency build. Cheap harness/source checks run before game launches, scenarios share a client/server session within each variant, and the matrix is generated from the same target manifest that validates the final receipts.
