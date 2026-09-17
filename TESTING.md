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
- uses vanilla's polygon-offset text render type instead of normal text depth state;
- does not use see-through rendering, which would allow images to draw through real occluders;
- remains paired with the current full `0..16` screen cube geometry.

The verifier has negative self-tests so a broken verifier cannot silently bless the exact regressions it is meant to catch. The live-client Python harness is also self-tested for target coverage and deterministic client/server fixtures.

## 2. JVM unit tests

Run:

```bash
./gradlew testAllVersions --no-daemon
```

This covers shared logic plus version-specific renderer/cache/UI/network behavior. It also compile-checks the Minecraft-specific render APIs used by all supported source sets.

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

A screenshot-only test is not used as the merge gate because raster output varies across drivers and CI software renderers. The deterministic merge gate is the render-state contract plus compile/unit coverage; the graphical live matrix protects real-client integration and startup behavior.

## Merge discipline

For renderer/runtime changes, merge only when the source/harness contract job, `testAllVersions`, and every selected live-client matrix job are green on the exact final PR head. Upload CI logs on failure rather than relying on console truncation.
