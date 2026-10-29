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
- keeps the authored world-model front flush on the full block face with no per-tile inset; the dynamic image plane at `0.501f` remains slightly in front and polygon-offset, avoiding both connected-screen seams and z-fighting.

The verifier has negative self-tests so a broken verifier cannot silently bless the exact regressions it is meant to catch. The live-client Python harness is also self-tested for target coverage and deterministic client/server fixtures.

## PacketWeave media regression checks

Initialize the pinned library with `git submodule update --init --recursive`. Shared tests cover reordered download chunks, malformed counts/indices, duplicates, changed envelopes, safe extensions, expiry, invalidation, disconnect and retry. Cache regression tests verify malformed complete downloads cannot poison disk, successful retries can publish, failed writes close decoded images, and latch-controlled invalidation/disconnect during decode cannot recreate deleted files. A clock-controlled test distinguishes the short failure retry cooldown from the longer in-flight request timeout. Native tests cover bounded packet decoding, upload binding and permission revocation. The pinned sources build a compile/test-only `:packetweave-api` project. Screens calls PacketWeave's public API from the separately installed native library and embeds neither public nor private PacketWeave classes. Production-jar checks reject both packages, including nested jars, and verify each target's declared runtime dependency floor. Shared progress tests exercise real file streaming through receipt-driven backpressure; native upload tests hold the image-storage monitor while checking transfer bookkeeping independently.

PacketWeave itself tests sender/receiver parity, global/per-owner budgets, dispatch failure/timeout, short reads, and file-size changes. Run `vendor/PacketWeave/gradlew :core:test -p vendor/PacketWeave --no-daemon` separately. A live join only checks connection/codec registration; it does not by itself prove a media upload/download.

Run the focused compiled-core co-installation regression with the JDK configured in CI and curl:

```bash
PACKETWEAVE_TEST_DEPENDENCY_CACHE="$PWD/.gradle/packetweave-coinstallation" \
  python3 -m unittest discover -s tools -p 'test_packetweave_packaging.py' -v
```

This opt-in test downloads checksum-pinned SecureJarHandler/ASM dependencies, compiles the pinned core and a separate consumer fixture, and exercises `JarModuleFinder` plus `resolveAndBind`. Its duplicate-core negative control must fail specifically for the public API split package. The consumer must resolve alongside the standalone library and invoke its public API from that provider. It is a module-resolution check, not a game launch. Without the environment variable, the regular Python suite skips this dependency-requiring test.

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

The harness requires the matching standalone PacketWeave native mod from the pinned submodule; `--with-packetweave` remains a compatibility alias. CI additionally runs with `--check-packetweave-dependency`: separate dedicated-server launches must reject missing PacketWeave and an intentionally old `0.1.0` metadata fixture with explicit dependency diagnostics before the valid client/server join. To reproduce CI, build `vendor/PacketWeave/targets/<target>` first using its own wrapper (Java 17 for Forge 1.20.1; Java 25 for the other targets), then keep Java 21 as `JAVA_HOME` for the Screens Gradle process. For **NeoForge 26.1.2**, also point `JAVA_HOME_25_X64` to an installed JDK 25, because the co-installation verifier loads native PacketWeave classes compiled for Java 25. CI sets this second home with `setup-java`; an ordinary local Java-21 environment does not:

```bash
# Required for neoforge-26.1.2; leave JAVA_HOME pointing at JDK 21.
export JAVA_HOME_25_X64=/absolute/path/to/jdk-25
python3 tools/live_join_test.py --target neoforge-26.1.2 --check-packetweave-dependency
```

This installs the standalone native jar into both test instances' `mods` directories and checks both actual production jars through SecureJarHandler before launching the client and server. The module check can also be run independently with `python3 tools/verify_packetweave_coinstallation.py --screens-jar <screens.jar> --packetweave-jar <packetweave.jar>`. The same `JAVA_HOME_25_X64` setting is required when that command inspects a NeoForge 26.1.2 native jar; otherwise the verifier falls back to `JAVA_HOME`/`java` and Java 21 cannot load its Java-25 bytecode.

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

Install the pinned OpenUI dependencies as in CI first. Java 21 runs Gradle; the build resolves the version-specific Java toolchains. For NeoForge 26.1.2, also export `JAVA_HOME_25_X64=/absolute/path/to/jdk-25` as described above so the standalone dependency verifier can load the Java-25 native jar. Linux needs Xvfb and Mesa, Windows needs a graphical desktop. The harness uses the existing dedicated-server and graphical-client launch targets. It snapshots the checkout under `build/screen-visual-work` and injects test drivers only there. Production sources/jars never contain these drivers or runtime switches to broken render types. CI builds and checks the production jar before instrumentation.

The server imports deterministic opaque-magenta and alpha-stripe PNGs with the production image manager, creates actual screen blocks and block entities, updates their image/anchor data, and teleports a spectator to the fixture. The client receives the ordinary screen updates and image download. It requires the intended image UUID, camera position, and actual renderer submission before taking samples. Client-side fake block state is not a substitute for server setup.

`tools/visual_cases.py` is the sole scenario manifest:

- Every supported loader/version target; all six facings.
- 2×2 and 8×8 screens at 8, 32, 64 and 160 blocks; frontal and 60-degree oblique views.
- A red foreground occluder covering half the screen, separately for every facing.
- A resource/shader reload case for every facing, with four samples before and four after successful reload.
- Cross-chunk boundary cases plus two ownership regressions: a real two-phase anchor-unload case that first synchronizes the complete screen at view distance 16 and then relies on server chunk tracking at distance 3, and a forced-child-owner case that keeps the real anchor block entity loaded while omitting its submission.
- A transparent/semi-transparent alpha case that checks a fully transparent stripe, a 50% alpha stripe, and an opaque reference in the same frame.
- Four samples per normal scene, one for each unique sub-degree raster alignment. Each camera offset is armed first and captured only after a subsequent real renderer submission; there is no fixed warm-up or inter-sample sleep. Every sample must pass.

The oracle projects the independently specified screen plane from recorded fixture geometry. Opaque cases check magenta in exposed screen interiors and image leakage in covered interiors; the alpha case normalizes the 50%-alpha band between the same frame's transparent backing and opaque reference. This preserves the semantic check across linear/sRGB framebuffer differences while still rejecting ignored or discarded alpha. It excludes silhouette edges and rejects insufficient pixel coverage or image color outside the expected silhouette. It never finds the expected region by searching for the image color itself. The error budget is 0.5% per region per sample; the worst sample determines the scene result. A missing texture, missing screen, stalled capture, crash or stale frame is not a successful visual check.

Each target runs isolated variants, reusing compiled outputs but starting fresh worlds/processes:

1. **Fixed**: all 113 scenarios and every sample pass, including the alpha/blending and anchor-fallback scenes.
2. **Plain text negative control**: for 1.20.1/1.21.1 targets, the renderer is changed back to plain text depth state and the disposable snapshot also restores the original coplanar authored front model so the old conflict remains reproducible. Every near reference must pass, and at least one 64/160-block scenario must show pixel corruption.
3. **See-through negative control**: every foreground case must show substantial occlusion failure. A crash cannot satisfy this control.

NeoForge 26.1.2 uses a hash-locked **original** control: the exact three-file renderer/state/client implementation from `main` at `1ec1058a8026294df8bd34ed21e6852d0244f26c`, with only the visual submission counter injected into the disposable snapshot. The common **plain** variant exercises the current single-quad ownership path with plain depth state and restored coplanar control models. Together these controls distinguish the actual pre-fix renderer failure from the current topology with depth bias removed without maintaining obsolete diagnostic-only variants.

The enlarged-offset mutation is rejected by the fast source/compiled contract, not by changing geometry to make the pixel reproduction easier. Negative controls mutate only the disposable snapshot. No assertion failure is retried into a pass.

`--compile-only` compiles every required instrumented/control variant for the selected target; `--probe` runs a diagnostic subset covering the north-facing distance sweep, other facings, small far screens, occlusion and reload. Both deliberately produce non-passing receipts and cannot satisfy the merge gate.

## Evidence and limits

`build/screen-visual-evidence/<target>/<run-id>/` retains frame PNGs, camera/scene metadata, graphics vendor/renderer/version, per-frame error measurements, server/client logs, crash reports and a `result.json` receipt. Receipts bind the run to the source commit and a snapshot digest; dirty local runs cannot certify an exact commit. The aggregate gate rejects missing/duplicate targets, stale/dirty receipts, incomplete controls and failed runs. Fresh unique directories and a checkout lock prevent old images/results from supplying a pass.

CI uses a software graphics backend and records its identity; that backend is not a guarantee about every physical GPU, shader pack, or optional renderer. The suite must first demonstrate the old-renderer failure on a repeatable configuration before it can certify the fix there. If the original bug cannot be reproduced, preserve the evidence and improve the fixture/backend—do not loosen thresholds or relabel startup success as visual proof. Third-party renderer and shader-pack matrices remain separate extensions; they are not claimed by this gate.

The five jobs share one OpenUI dependency build. Cheap harness/source checks run before game launches, scenarios share a client/server session within each variant, and the matrix is generated from the same target manifest that validates the final receipts.

## Release dependencies and upgrade documentation

Screens requires the **compatible native PacketWeave release on both sides**. Resolve the precise minimum from `gradle.properties`, target loader metadata and the release changelog rather than copying a version into this evergreen testing guide. Publish the matching PacketWeave artifacts first, verify all required native targets on GitHub and CurseForge, and only then publish Screens. `tools/release_dependencies.py verify-release` blocks Screens releases if required GitHub artifacts are unavailable. The CurseForge upload step checks the official dependency catalog for the exact PacketWeave project ID/slug and declares `requiredDependency` for every target; version floors remain enforced by loader metadata because CurseForge relations cannot express them.

Accepted uploads retain per-target file-ID/source-SHA/relation receipts. They do not prove moderation approval or public download availability. Inspect those public file pages and their dependency relations before declaring publication complete. An ambiguous failed upload is not automatically retried as a POST or reported as success.

## 5. Shader image color preservation

The shader-specific development installation, supported pinned matrix, precise
opaque-color contract, transparency/postprocessing limitations, and real
Complementary framebuffer controls are in [tools/shader/README.md](tools/shader/README.md).
Run `python3 tools/shader_test.py --target <target>` after the normal dependencies
are built. Renderer changes also require **Required shader color evidence** on
the final exact PR head. Existing vanilla depth evidence does not imply shader
compatibility, and source/unit tests do not imply a successful game launch.

## Optional Sable integration regressions

Run `./gradlew :common-1.21.1:test --tests '*SableCompatibilityMetadataTest'`
to check actual loader dependency declarations, and run the complete
`:common-1.21.1:test` suite for spatial transform behavior. The range
tests exercise Fabric Loader's `VersionPredicate` and NeoForge's Maven
`VersionRange` semantics: supported releases must be admitted, unsupported
releases rejected, and Sable loading kept optional. Fabric's `suggests`
declaration is advisory; NeoForge checks the optional dependency if installed.

Consult the selected release's loader metadata and release notes for currently
supported integrations. Past source comparisons, exact Sable/Companion versions,
release tags and qualification limits are archived in the
[compatibility history](docs/native-acceptance-history.md#sable-compatibility-checkpoint),
not pinned in this evergreen testing guide.

## Network access regressions

`testAllVersions` includes `ScreenNetworkServiceTest` and `ScreenAccessServiceTest` for all three Minecraft adapters. They test permission separation, legacy migration, empty-content restart recovery, backup corruption and failed-write rollback, immutable/bounded trust membership, private download packet rejection before file scheduling, tracked displays, chunk/dimension/disconnect revocation, replaced tiles, local content overrides, and source/destination ownership on relinks. See [the access guide](docs/network-access.md) for the complete contract and manual multiplayer limits. Existing live-join, framebuffer and shader checks still apply to all five loader targets.

## Optional Patchouli guide

`tools/test_patchouli_resources.py` covers optional loader metadata, loader-gated recipes (including rejection of missing conditions), guide references, page text budgets, and the transparent 64x64 texture format. `tools/assert_production_jar.py` also verifies physical book discovery, version-specific book models, contents, and recipe identity in each production jar. Native development runs include Patchouli by default; use `-PscreensPatchouli=false` to verify Screens without the guide dependency. See [guide integration](docs/patchouli.md). Page length checks are conservative resource checks, not live font/layout validation.

Personal display preferences have core persistence/migration coverage. Start a client with JVM flag `-Dsimplyscreens.personalSettingsTest=true` to run eight native UI cases in both themes and four sizes. The probe verifies tooltip visibility, real slider input, disk persistence and reopening without a world or management permissions, saves screenshots, prints `SIMPLYSCREENS_PERSONAL_SETTINGS_PASS`, then exits. Use a disposable game directory.

## Playlist acceptance

The shared `playlist/ScreenPlaylistServiceTest` suite exercises real persisted timeline data: named CRUD, immutable running snapshots, bounded queues, pause/resume/restart/repeat/shuffle, exact announcement restore, image purge, restart offsets, transaction rollback, malformed-store recovery and randomized reload sequences. Native `ScreenPlaylistRuntimeTest`, `ScreenPlaylistPacketTest` and `ImageLoadScreenPlaylistTest` cover current permissions and target/revision guards, private-image rejection, local overrides, static-selection restart behavior, packet limits, stale responses and repeated-click suppression in each source version.

Run `testAllVersions` for the full native suites. Run the test-only `tools/playlist_test.py` harness for real linked-screen/client/restart evidence across all five targets. The harness creates disposable instrumented copies after checking production jars; its probes must never ship. A join smoke or unit pass alone does not establish the playlist acceptance criteria.

`PlaybackTickClockTest` covers a paused integrated server and tick-counter rollover. Native editor regressions cover non-pausing screens, starting a selected saved list from an empty session, creation followed by adding the originating image, and slow read replies surviving periodic polling. Populated layout tests set the actual screen dimensions and check player/action bounds and at least two complete slide rows at five viewport sizes. Also test single-player directly: start a two-slide list, keep its editor open, pause the game, and resume; the first slide must neither blank immediately nor consume the paused time.

Playlist gate commands:

```sh
python3 tools/playlist_test.py --target fabric-1.21.1
python3 tools/playlist_test.py --target neoforge-26.1.2 --compile-only
python3 tools/playlist_test.py --verify-receipts downloaded-playlist-evidence --head "$GITHUB_SHA"
```

The live-client workflow runs this gate on all five targets and requires fresh, clean, exact-head receipts. Each target records 23 server/client scenarios, six populated Gallery/Playlists/Queue screenshots at normal and narrow window sizes, and exact pre-shutdown/boot timeline comparisons for paused and running restarts. Compile-only results deliberately cannot satisfy the gate. Evidence and logs are under `build/screen-playlist-evidence/`; instrumented working copies stay under `build/screen-playlist-work/`.

The playlist harness stops dedicated servers through the test driver's normal Minecraft `halt(false)` path, then requires a natural zero process exit and exact shutdown/boot cursor equality. This also works with NeoForge's ModDev launcher, which does not forward console stdin. Architectury Runtime 5.2.91 leaves two development-transformer fixed thread pools open after Minecraft finishes; a disposable, version-checked Java agent changes only those two known launcher pools to daemon workers. The gate requires exact patch receipts, rejects missing/changed classes, and never changes Minecraft/mod executors or accepts a killed server as restart evidence. The agent is outside production source sets and its package is forbidden in released jars. On any shutdown failure, JVM-specific thread and process diagnostics are retained.
