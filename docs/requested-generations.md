# Native ports for the requested generations

These targets are standalone builds. They deliberately do not configure the older
root Loom projects, and do not remove or change the existing 1.20.1, 1.21.1, or
26.1.2 release lanes. The public Screen API, operator commands, playlist and
ownership/security behavior on current main remain part of every port.

| Minecraft | Native loaders | Runtime | Build runner |
| --- | --- | --- | --- |
| 1.12.2 | Forge 14.23.5.2864 | Java 8 | Gradle 5.6.4 on Java 8; Java 17/Jabel compiler |
| 1.16.5 | Fabric; Forge 36.2.42 | Java 8 | Fabric Gradle 9.5.1; Forge Gradle 8.14; Java 17/Jabel compiler |
| 26.2 | Fabric; NeoForge 26.2.0.93 | Java 25 | Gradle 9.5.1 on Java 25 |

There is no official Fabric 1.12.2 release, so this project does not introduce an
unofficial Legacy Fabric dependency. 26.2 uses Fabric and NeoForge, not Forge.

## Dependencies are separate mods

The ports require native PacketWeave 0.1.4 and OpenUI MC 0.0.15. These are
source-built dependencies while their port PRs are unmerged, not assumed public
releases. PacketWeave's API is compile-only and must never be shaded into Screens.
Fabric checks OpenUI before client entrypoints load. Forge 1.12.2 also declares
OpenUI as a loader dependency on servers, where its entrypoint is inert.
Dedicated servers do not initialize UI, image textures or renderers.

Build pinned sibling checkouts next to Simply-Screens, then build a target from
its own directory. Each target has its own wrapper. The modern targets accept
-PopenuiJar=/absolute/native.jar and -PpacketweaveJar=/absolute/native.jar; absent
those flags, they use matching sibling target build/libs artifacts. Do not
substitute a library jar from another Minecraft generation or loader.

PacketWeave reference: UpperMoon0/PacketWeave PR #4, commit
f5ef9b8c3a4ab3ba3af82af54769a09f370c1a85. OpenUI source pin:
UpperMoon0/OpenUI-MC commit 5feba85ab1363a7a3e71f4fbf2c1347911cf326d.
The requested-generations workflow builds both immutable source pins separately,
then each native Screens target; it never publishes a release or deploys a server.

For each target, run its wrapper with `-p <target> build --no-daemon --max-workers=2`.
Build OpenUI with the same target wrapper and `build publishToMavenLocal`; the
consumer uses the resulting native file, not an assumed remote release. Use
Java 25 to drive Fabric/26.2 builds, Java 21 for Forge 1.16.5, and Java 8 plus
`JAVA17_HOME` for Forge 1.12.2. The Java 8 test launcher remains distinct from the
Java 17 source compiler.

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

## Initial source validation (2026-10-21)

Before native CI, the cloud workspace completed these independent checks against
real mapped game libraries and native loader APIs (not API stubs):

- Forge 1.12.2: 101 complete production sources and 32 test sources compiled;
  180 JUnit cases passed on Java 8, zero skipped. The game classpath had the exact
  official Forge merge/binary patches and MCP stable 39 remapping applied.
- Fabric 1.16.5: 97 complete production sources compiled with Java 8 platform
  APIs; Forge 1.16.5: 96 complete production sources compiled with the native MCP
  class identities and official member names. All 162 shared/legacy tests passed
  on Java 8, zero skipped.
- Fabric 26.2: 95 complete production sources compiled; NeoForge 26.2: 94.
  All 248 shared and modern adapter tests passed on Java 25 after real Minecraft
  registry bootstrap, zero skipped. This direct run does not include NeoForge's
  loader-specific PacketWeave provider test; the native CI lane runs that test.
- The existing Python suite completed 163 cases with 157 passing and six explicit
  environment skips (five optional Java/toolchain harness cases and the optional
  SecureJarHandler compiled-core check). The four real Fabric prelaunch guards
  passed server/client dependency contracts. Additional Gradle input/version
  parser regressions passed after that suite.

These are compile/test results, not native Gradle packaging or live acceptance.
The workflow must establish remapping, loader discovery, real dedicated-server
startup and client join/UI evidence separately. No production-jar live launch,
rendered media pixel comparison, shader parity, GL/Vulkan parity, resource reload,
transparency or occlusion result is claimed by this initial record.
