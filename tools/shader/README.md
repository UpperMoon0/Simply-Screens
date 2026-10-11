# Shader image color regression

## Contract

Iris and Oculus replace the full-bright world text shader and then run pack-defined
composite/final programs. Full-bright vertices cannot opt out of tonemapping or
color grading. Simply Screens therefore retains its original depth-tested world
image draw (including the pack's bloom contribution), then restores fully opaque
source-image samples after `GameRenderer` calls `LevelRenderer.renderLevel`.
This is before Minecraft clears world depth for hands and before Iris's display
color-space conversion. The replay tests the existing world depth, writes color
only, uses the original immutable projection/pose/UVs, and performs no lighting,
fog, grading or alpha blending.

Opaque interior RGB must match the decoded source texture to within 4/255 per
channel, with at most 1% violating samples per independently projected chart
patch. Every frame and every visible patch must pass. This is a display-space
byte contract before optional display/OS color management, not a colorimetric
measurement of a monitor. Surrounding shader bloom remains present. The opaque
image itself does not retain a color-changing bloom overlay.

Semi-transparent and filtered-edge samples remain in the shader's world pass.
Repainting their alpha would blend them twice, and their displayed RGB depends
on the shader-processed scene behind them. This implementation deliberately does
not promise source RGB for those samples. It also cannot guarantee correct
reconstruction under arbitrary screen-space refraction, depth rewriting, lens
warps, shader-provided motion blur, or third-party postprocessing after the hook.
TAA and silhouette boundaries are excluded from the interior color measurement;
this does not certify every GPU or shader option.

## Development installation

Use matching target names, never mix Forge Oculus/Embeddium with Fabric Iris/Sodium.
The lock records official Modrinth version IDs, game/loader metadata, URLs and
SHA512 digests. Downloads and the Gradle development classpath independently
verify the hashes. Nothing is shaded into production artifacts.

```sh
python3 tools/shader_stack.py --target fabric-1.21.1 --client fabric-1.21.1/run
./gradlew :fabric-1.21.1:runClient -PscreensShaderStack="$PWD/build/shader-dependencies"
```

Build/install the project's normal OpenUI and PacketWeave development dependencies
as documented in `TESTING.md`. `--client` explicitly writes only that development
client's native shader configuration (`iris.properties`, or `oculus.properties`
on Forge) and installs Complementary in its shaderpacks folder.

Pinned matrix:

| Target | Shader loader | Renderer | Extra |
|---|---|---|---|
| Fabric 1.20.1 | Iris 1.7.6 | Sodium 0.5.12-beta.2 | Sodium Extra 0.5.9 |
| Fabric 1.21.1 | Iris 1.8.8 | Sodium 0.6.13 | Sodium Extra 0.6.0 |
| Forge 1.20.1 | Oculus 1.8.0 | Embeddium 0.3.31 | Embeddium/Rubidium Extra 0.5.4.4 |
| NeoForge 1.21.1 | Iris 1.8.12 | Sodium 0.6.13 | Sodium Extra 0.6.0 |
| NeoForge 26.1.2 | Iris 1.11.4 | Sodium 0.9.2 | Sodium Extra 0.9.4 |

All targets use Complementary Reimagined r5.9.3. Sodium Extra itself is not a Forge
mod; the pinned Forge port is named Embeddium (Rubidium) Extra. The NeoForge26.1.2 Extra artifact hash and native metadata are verified; runtime coverage still requires the actual test to pass. Compatibility
metadata is checked before installation, and runtime loader rejection is a test
failure, never permission to edit a third-party jar's metadata.

## Real framebuffer gate

```sh
python3 tools/shader_test.py --target fabric-1.21.1
```

The disposable snapshot uses the existing real dedicated-server/image-download
fixture and graphics runtime. It imports a 16-patch RGB/grayscale chart through
the production image manager. Six facings, near/oblique views, foreground
occlusion, shader-resource reload and alpha cases are captured at four raster
alignments. The main renderer's world submissions must match the server fixture;
shadow-map submissions cannot satisfy readiness.

Eight isolated client/server runs use the same pinned renderer/mod stack:

1. Shader off: validates the source-color baseline on all26scenes.
2. Original world pass: disables only the late replay; must demonstrate real chart
   color error rather than a crash or inactive shader, on the full matrix.
3. Fixed: source colors and occlusion pass with default temporal AA on all26scenes,
   including every previously failing far/oblique raster sample.
4. Original static: world-only reference with temporal AA disabled.
5. Fixed static: corrected reference with the same temporal-AA-disabled profile.
6. Fixed repeat: identical static settings in a fresh process. Alpha p95 must be
   at most1/255, ring mean absolute delta at most1/255 and signed delta at most0.05/255.
7. Bloom off: only `BLOOM_ENABLED` differs from the fixed-static reference.
8. Glow source off: same profile/replay, but suppress original world image vertices.

The glow/alpha fixtures have a black-concrete backdrop behind the screen, whose
block arrival is verified before terrain/frame readiness. Bright sky can be
darkened by a valid bloom blend; it is not a useful positive-emission reference.
The ring is selected only from geometry and must brighten against this dark surface.

Variants4–8 cover the frontal NORTH glow chart and alpha fixture only; variants1–3
retain the full color/depth/reload matrix. Both glow controls require the geometric
ring to brighten by more than0.15/255 in every sample. This separates image-produced
glow from background bloom, without using mismatched temporal-AA history as proof.

Semi-transparent/transparent samples are compared to the original shader run to
reject double blending. The test uses `BLOOM_STRENGTH=0.32` for a measurable halo;
that is a test fixture setting, not a production override of the user's pack.
The paired profile disables pack clouds, cloud shadows, animated light shafts,
motion blur and waving through public options. World day and weather advancement
are frozen with the version-appropriate gamerules. Default temporal AA remains
active for the full source-color/depth matrix, including its projection jitter;
it is disabled only for the paired alpha/glow measurements. The pack options are verified against
the pinned shader source and recorded in receipts. The identical-process control
fails closed if residual temporal drift is large enough to confound the bloom or
alpha thresholds; no dynamic-sky equivalence is claimed.

The replay uses one additional raster-pixel slope bias compared with the world
image, plus a small depth-precision margin. This addresses whole-plane LEQUAL
rejection at far oblique camera phases without disabling depth tests or writing
depth. All six facings include32-block/60-degree foreground occlusion as well as
near frontal occlusion. Nearly coplanar geometry inside the bounded polygon-offset
margin, arbitrary pack depth rewrites and geometric warps remain limitations.

Receipts preserve exact commit, clean/dirty status, source snapshot digest,
artifact hashes, actually loaded mod IDs/versions, scene/camera data, frame PNGs, graphics identity, original-control
measurements, transparency deltas and bloom-ring deltas. The aggregate gate checks every required frame/metadata file against its SHA256 and revalidates controls and loaded-mod proof. `Required shader color
evidence` rejects missing targets, stale/dirty runs, wrong stacks, inactive
shaders, incomplete controls and failed pixels. Source contracts and synthetic
oracle tests do not replace this gate. A newly opened draft has no compatibility
claim until its exact-head real shader jobs pass.

## Primary-source references

- [Iris 1.21.1 level finalization](https://github.com/IrisShaders/Iris/blob/1.21.1/common/src/main/java/net/irisshaders/iris/mixin/MixinLevelRenderer.java)
- [Iris shared main depth / final pass](https://github.com/IrisShaders/Iris/blob/1.21.1/common/src/main/java/net/irisshaders/iris/pipeline/FinalPassRenderer.java)
- [Oculus public Iris API](https://github.com/Asek3/Oculus/blob/1.20-1.6.15/src/main/java/net/irisshaders/iris/api/v0/IrisApi.java)
- [Iris 26.1 level finalization](https://github.com/IrisShaders/Iris/blob/26.1/common/src/main/java/net/irisshaders/iris/mixin/MixinLevelRenderer.java)
- [Iris 26.1 output conversion](https://github.com/IrisShaders/Iris/blob/26.1/common/src/main/java/net/irisshaders/iris/mixin/MixinGameRenderer.java)
- [Complementary grading, bloom and tonemapping](https://github.com/ComplementaryDevelopment/ComplementaryReimagined/blob/main/shaders/program/composite5.glsl)
- [Complementary official pinned release](https://modrinth.com/shader/complementary-reimagined/version/Bqen1mJX)

NeoForge1.21.1 deliberately accepts Minecraft1.21 dependency ranges through its official [VersionSupportMatrix](https://github.com/neoforged/FancyModLoader/blob/1.21.1/loader/src/main/java/net/neoforged/fml/loading/VersionSupportMatrix.java); the Iris native jar is used unchanged.

The shader-disabled 32-block baseline retains Minecraft’s normal distance fog; it permits
12/255 source error at that distance only. All corrected shader-on chart patches retain
the strict 4/255 tolerance. Client occluder block states must synchronize before the
terrain rebuild gate, so a missing wall cannot silently count as a rendering regression.

Development dependencies expand required nested libraries from the SHA-512-verified
parent jars because [Loom's ModProcessor](https://github.com/architectury/architectury-loom/blob/1.11/src/main/java/net/fabricmc/loom/configuration/mods/ModProcessor.java)
strips nesting metadata during remapping. Fabric API components already supplied by
the build are not duplicated; the pinned NeoForge21 Iris renderer-api build takes
precedence over Sodium's older build; Extra's MixinExtras0.4.1 satisfies Embeddium's
0.3.5-or-later range. Only these explicit pinned-version resolutions are allowed.
Unexpected child identity/version conflicts fail closed.
Fabric20 uses Java17 for the game while Gradle runs Java21, through Loom's
[runtime compatibility property](https://github.com/architectury/architectury-loom/blob/1.11/src/main/java/net/fabricmc/loom/configuration/providers/minecraft/MinecraftLibraryProvider.java),
preserving Sodium's required LWJGL3.3.1. Source mixin configurations omit refmaps;
[Architectury's production transforms](https://github.com/architectury/architectury-plugin/blob/3.4/src/main/kotlin/dev/architectury/plugin/ModLoader.kt)
add them for Fabric/Forge and statically remap NeoForge.

For Loom Forge/NeoForge, non-mod children use the documented
[forgeRuntimeLibrary bootstrap classpath](https://docs.architectury.dev/loom/using_libraries/),
not only localRuntime. This is required for MixinExtras and Sodium’s service classes.

Forge development launches register the color mixin only on client runs; production
jars retain the explicit `MixinConfigs` manifest entry. This prevents a client-only
launch argument from masking the server's real missing-dependency rejection. The
production-jar gate requires the hook to exist and be registered in every loader's
actual packaged metadata. Relevant Loom sources: [development launch arguments](https://github.com/architectury/architectury-loom/blob/dev/1.11/src/main/java/net/fabricmc/loom/task/launch/GenerateDLIConfigTask.java)
and [production manifest handling](https://github.com/architectury/architectury-loom/blob/dev/1.11/src/main/java/net/fabricmc/loom/task/RemapTaskConfiguration.java).
