# Native ports for the requested generations

These targets are standalone builds. They deliberately do not configure the older
root Loom projects, and do not remove or change the existing 1.20.1, 1.21.1, or
26.1.2 release lanes. The public Screen API, operator commands, playlist and
ownership/security behavior on current main remain part of every port.

| Minecraft | Native loaders | Runtime | Build/toolchain policy |
| --- | --- | --- | --- |
| 1.12.2 | Forge | Java 8 | Legacy loader wrapper; separate Jabel-compatible compiler JDK |
| 1.16.5 | Fabric; Forge | Java 8 | Per-loader Gradle wrapper and legacy backport toolchain |
| 26.2 | Fabric; NeoForge | Java 25 | Native loader wrapper and target-specific toolchain |

Exact loader, mapping, Gradle and compiler versions come from the target's
build files and CI toolchain matrix, not this evergreen guide.

There is no official Fabric 1.12.2 release, so this project does not introduce an
unofficial Legacy Fabric dependency. 26.2 uses Fabric and NeoForge, not Forge.

## Dependencies are separate mods

The ports require native PacketWeave and OpenUI MC matching the [source lockfile](../tools/requested-dependencies.lock.json) and each target's loader metadata. Publish the required matching native dependency artifacts before releasing these ports. The release workflow verifies the requested artifacts for each dependency before building the locked sources. PacketWeave's API is compile-only and must never be shaded into Screens. Minimum compatible versions are defined by the selected release's metadata, not by this evergreen guide.
Fabric checks OpenUI before client entrypoints load. Forge 1.12.2 also declares
OpenUI as a loader dependency on servers, where its entrypoint is inert.
Dedicated servers do not initialize UI, image textures or renderers.

Build pinned sibling checkouts next to Simply-Screens, then build a target from
its own directory. Each target has its own wrapper. The modern targets accept
-PopenuiJar=/absolute/native.jar and -PpacketweaveJar=/absolute/native.jar; absent
those flags, they use matching sibling target build/libs artifacts. Do not
substitute a library jar from another Minecraft generation or loader.

Exact PacketWeave and OpenUI source commits are maintained in the machine-readable
[`tools/requested-dependencies.lock.json`](../tools/requested-dependencies.lock.json).
The verification suite checks that the lockfile and native CI workflow agree; the
evergreen documentation deliberately avoids duplicating release-specific pins.
The requested-generations workflow builds both immutable source pins separately,
then each native Screens target; it never publishes a release or deploys a server.

For each target, run its wrapper with `-p <target> build --no-daemon --max-workers=2`.
Build OpenUI with the same target wrapper and `build publishToMavenLocal`; the
consumer uses the resulting native file, not an assumed remote release. Select
JDKs using the per-target build files and CI toolchain matrix. Legacy runtimes
can require a separate compiler JDK from their runtime/test launcher.

## Native adaptations

- Java 8 targets derive the complete maintained implementation, rather than
  exposing registration stubs. Record value semantics and modern collection/string
  operations have Java 8 equivalents; --release 8 verifies the platform API.
- The 1.16.5 block entity hooks use the native attachment lifecycle so ownership,
  media indexes and load reconciliation are restored after loading a world.
- URL imports keep public-host validation, disabled redirects, per-player bounds,
  bounded queues, connection/read timeouts and encoded/decoded image limits.
- Pre-1.17 recipes use quartz in place of nonexistent amethyst. Recipes, loot and
  language files use each generation's native resource formats. The 1.16 guide
  uses an owned optional recipe serializer because that Patchouli generation does
  not provide the newer shaped-book serializer. Missing Patchouli never enables
  a recipe with an invalid result.
- Legacy packet receivers check the receiving environment before decoding, in
  addition to the native direction-specific channel registration.
- 26.2 rendering uses Blaze3D bind groups, transient vertex uploads and render
  passes for OpenGL/Vulkan compatibility. It honors 26.2 reversed-Z depth testing
  and positive polygon bias; it never bypasses world occlusion.
- 1.16.5 uses its native fixed-function render path and carefully restores the
  GL state of its optional shader color-replay pass. This code is not included
  in the 26.2 targets.

## Verification levels

A successful compile is not a gameplay result. Track separately: full source
compilation, shared and adapter tests, native remapping/packaging, dedicated-server
classloading, and actual client rendering/UI/media behavior. See the PR's final
verification record for exact passed, failed and unrun checks.

## Runtime acceptance

After native packaging, `python3 tools/requested_live_join.py --target <target>`
starts a loopback-only dedicated server and real auto-joining client using each
standalone loader. The existing opt-in UI probe opens tabs, resizes and closes
the OpenUI screen. The runner requires a server-ready marker, an actual joined
player/world, the completed UI marker and a clean client exit before writing an
exact-head receipt. It preserves both logs. A display or Xvfb is required.

This is a native **development-classpath** launch, not proof that a production
jar was launched. Production jar identity and dependency ownership are checked
separately. It also does not establish source-image pixel accuracy, shader
compatibility, Vulkan/OpenGL parity, resource reload, transparency or world
occlusion. Those remain separate live-world/framebuffer acceptance checks.

## Shared-code ownership and drift prevention

The newer-generation ports derive maintained gameplay behavior from the canonical
implementation through `tools/modern_screen_sources.py` with narrowly scoped native
rendering adapters. Legacy ports likewise derive shared services and behavior from
canonical sources through the legacy generators. Generated source is not an
independently maintained fork. Tests check generator completeness, reproducibility,
native overlay boundaries, and stale-output cleanup.

## Native testing and release limitations

Native packaging, dedicated-server startup, authenticated or fixture-based client
joining, GUI/media checks, and framebuffer qualification are distinct gates. A
successful build or unit test is not evidence of actual network impairment,
visual correctness, production-jar launch, or real-account multiplayer eligibility.
CI test fixtures must identify simulated authentication explicitly. Preserve source
identity, logs, dependency manifests and exact-head receipts for every claimed
acceptance result.

The release workflow builds the native target matrix using pinned dependency
artifacts, validates required production JARs and dependency relations, and blocks
release when a prerequisite is unavailable. Update the selected release's
changelog with wire-format changes and installation requirements; never treat a
configured workflow as a passing acceptance result.

For dated build evidence, native renderer investigations, past test outcomes, and
account/CI fixture history, consult [native acceptance history](native-acceptance-history.md).
These historical results are not statements about the current PR head.
