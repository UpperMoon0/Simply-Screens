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

The ports require native PacketWeave 0.1.5 and OpenUI MC 0.0.15. PacketWeave 0.1.5 is supplied by the linked transfer-safety PR and must be published before releasing these ports. The release workflow verifies
all five requested artifacts in each dependency release before building these
exact source pins; the existing target dependency floors remain unchanged. PacketWeave's API is compile-only and must never be shaded into Screens.
Fabric checks OpenUI before client entrypoints load. Forge 1.12.2 also declares
OpenUI as a loader dependency on servers, where its entrypoint is inert.
Dedicated servers do not initialize UI, image textures or renderers.

Build pinned sibling checkouts next to Simply-Screens, then build a target from
its own directory. Each target has its own wrapper. The modern targets accept
-PopenuiJar=/absolute/native.jar and -PpacketweaveJar=/absolute/native.jar; absent
those flags, they use matching sibling target build/libs artifacts. Do not
substitute a library jar from another Minecraft generation or loader.

PacketWeave reference: UpperMoon0/PacketWeave PR #4, commit
88046f276ed059df4b9c2f1c0e1d643d26472b1c. OpenUI source pin:
UpperMoon0/OpenUI-MC commit 672b5b2b7169bef30f5f10b34f296812abe31460.
The machine-readable `tools/requested-dependencies.lock.json` is checked against
both this document and the workflow so native source pins cannot silently drift.
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

## Shared-code ownership and drift prevention

The 26.2 runtime reuses the maintained 26.1.2 gameplay implementation through
`tools/modern_screen_sources.py`. Protocol codecs, security, commands, public API,
UI state, media/persistence logic and the block-entity renderer are not separately
maintained copies. Only five native pipeline/mixin adapter files and one native
render-state test remain under `common-26.2`. Common domain services continue to
come directly from `common/`. The existing 26.1.2 release source is not changed.

The generator performs only receiver-specific Minecraft GUI routing substitutions
outside those native overlays. It also derives the 14 existing modern test classes
with real registry bootstrap and reuses canonical modern resources. Regression
checks require the full class set, exact canonical transformation, deterministic
output, stale-output removal and native-only overlay boundaries. The first
migration was additionally checked token-for-token against all 54 production
sources and all 14 tests that had already passed the complete compile/test run.

The single-source 26.2 refactor was revalidated after generation: all 95 Fabric
and 94 NeoForge production sources compile, and all 248 shared/native Java 25
regressions pass with zero skipped. At remote head `8076df49`, Fabric 26.2 also
completed native packaging, jar checks, and an actual dedicated-server/client
join with the OpenUI tab/resize smoke probe. That development-runtime receipt is
not framebuffer, media, shader, or production-jar runtime acceptance.

## Review corrections and native launch diagnostics

The legacy texture-ownership correction is shared by both source generators.
Owned registrations are removed by identity before one native close; failed media
share a single dedicated error texture. Stable locations do not grow the native
texture manager's prefix counter. A subsequent full native compilation passed
with 168 Java 8 tests for 1.16 and 184 Java 8 tests for Forge 1.12. These ownership
tests use real native manager registrations with mocked GPU textures, not a live
GL context or a resource-reload rendering acceptance result.

ForgeGradle 3's notch-obfuscated path otherwise chooses the old plain 1.12.2 MCP
merge config, which injects an incompatible three-value Side enum. The narrow
build repository adapter selects Forge userdev's official timestamped
1.12.2-20200226.224830 merge instead (`--inject false`). A comment-only access
transformer supplies a build-local cache identity; it changes no game access
flags, ships no game classes, and does not delete global caches. The helper has
passed offline Gradle 5.6.4 / Java 8 configuration and fail-closed negative
fixtures. A missing or failed pin never selects the known-bad legacy fallback;
corrected native packaging and launch subsequently passed on Forge 1.12.2 at
`bb44c3726adaea17b729b5baa635057f923dad11`.

The native probes do not suppress loader warnings or dismiss native error screens.
Reused isolated directories with an old `showLoadWarnings=false` configuration
fail before launch. Actual logs remain part of each acceptance record; a joined
client is not a warning-free-loading claim.

For a native 26.2 visual run, build the pinned native dependency artifacts,
then use `python3 tools/requested_visual.py --target fabric-26.2` (or
`neoforge-26.2`). The `native-reversed-z-v1` qualification retains every common
fixed-renderer case, frame-stability check, reload/alpha/occlusion check and pixel
threshold. It additionally requires rejection of a native wrong-depth comparison
in all six orientations and all four samples per orientation (24 samples). Those
near scenes are identical to scenes already required to pass in the fixed run.
The only mutation is the depth comparison: native `GREATER_THAN_OR_EQUAL` becomes
legacy `LESS_THAN_OR_EQUAL`; geometry, shaders, blending, depth writes and bias
remain unchanged. Vanilla 26.2 uses reversed-Z, so this control represents an
incorrect depth-state port rather than an invented historical geometry failure.

The hash-locked historical plain renderer still runs with the same cases. Its
result is recorded separately as `pass` (historical defect reproduced) or
`inconclusive` (not reproduced), never silently relabeled. Historical near-reference,
infrastructure, incomplete-evidence or other failures remain fatal. On the observed
26.2 Mesa backend the plain control rendered cleanly even at 64/160 blocks; this
does not establish historical forward-Z z-fighting reproduction. Historical
inconclusive evidence no longer substitutes for or blocks the explicitly approved
native reversed-Z qualification; both meanings remain visible in schema-2 receipts.

Instrumentation is generated only into a disposable source snapshot. `--probe`
and `--compile-only` cannot produce a verifiable passing full-gate receipt.
`--verify-receipt <result.json> --head <commit>` requires the explicit native
qualification policy, complete fixed/occlusion/wrong-depth outcomes and case
manifest, separate historical result, exact clean head/source/dependency identity
and consistent graphics backend. No ordinary renderer or old five-target visual
or shader acceptance contract changes.

The requested-generations CI runs this full framebuffer qualification on
representative Fabric 26.2 after its native join/UI smoke passes. A configured
step is not a completed result: the actual native wrong-depth control and final
receipt must pass before qualification is claimed. It does not establish another
backend, shaders, production-JAR launch or the historical defect's reproduction.
The 1.12 native UI/screenshot adapter remains separate from framebuffer qualification.

## Native acceptance checkpoint and separate legacy media probe

At `bb44c3726adaea17b729b5baa635057f923dad11`, Forge 1.12.2 completed native
packaging/resource checks, dedicated-server startup, actual client join and
OpenUI tab/resize smoke with wrapper exit zero. The evidence is in
[run 39622599645](https://github.com/UpperMoon0/Simply-Screens/actions/runs/39622599645).
This run did not yet enable the newer legacy media probe. Ignored ASM module-info
scan errors, the existing FML test-exit rerouting warning, and unavailable headless
OpenAL/narrator libraries remain visible in logs; this is not warning-free loading.

The shared opt-in legacy media probe is authoritative under `common-legacy-game`.
Both legacy generators adapt it rather than maintaining separate tests. It uses
existing production image-upload/download packets, verifies stored ownership and
PNG identity, checks decoded native pixels and registered texture, observes
submission by the actual screen renderer, and requires identity-scoped cleanup.
It does not claim framebuffer pixel correctness, reload, alpha/occlusion, shader
compatibility, or production-jar runtime qualification. The additional source
has compiled on all three legacy loaders, with 11 Java 8 neutral tests passing;
its actual media runtime receipt is still required.

The real 1.16 clients reported native multiplayer eligibility false. The dedicated
lane fails closed, records the observed refusal only after clean teardown, and
never changes account/service state or forces a connection. A separately
permitted `requested_integrated_acceptance.py` lane requires that exact target/head
refusal receipt. It clicks only enabled, visible ordinary vanilla Singleplayer
and Create World controls, then exercises OpenUI and the shared native media
probe in the actual integrated server. It requires wrapper exit zero and complete
matching server/client media evidence. Its receipt explicitly remains neither a
dedicated-server join nor a multi-client test; the original dedicated CI step
remains failed. A configured integrated lane is not a completed gameplay result.

## Isolated 1.16.5 CI service fixture

The 1.16.5 CI jobs now explicitly use `requested_live_join.py --service-fixture`.
This supersedes the dedicated CI refusal described above: the launcher defaults
(`FabricMC` and `DONT_CRASH`) are development placeholders, not authenticated
Minecraft sessions. CI must not depend on the public privileges service's response
to those placeholders.

The fixture uses Authlib 2.1.28's native `minecraft.api.*.host` configuration on
only `runLiveJoinTestClient`. It binds an ephemeral IPv4 loopback port, accepts
only the literal `SimplyScreensCiFixture` token and serves privileges/blocklist
responses. No production source, Authlib bytecode, account, native permission
flag or connection screen is changed. Ordinary client launches retain their
original service settings.

Every CI invocation first supplies `multiplayerServer.enabled=false` and requires
the actual loaded client to report native refusal, consume the fixture and never
enter gameplay. It then supplies `true` and requires the existing dedicated join,
OpenUI, media identity, decode, texture, submission and clean shutdown contracts.
Both phases preserve logs. Missing service requests, an unexpected denied join,
crashes and teardown failures fail the job and leave no success receipt.

The final receipt identifies `authentication.mode=loopback-service-fixture` and
`realAccountQualified=false`. It proves native development networking with a
simulated external service, **not real-account authentication or public-service
eligibility**. Without the explicit option, the original native-refusal behavior
remains unchanged. The separate Singleplayer fallback still requires an exact
native refusal receipt; fixture failures cannot authorize it.

## Release delivery

The release workflow builds all ten native targets, including the five requested
standalone generations, with their own pinned dependency artifacts and Java
contracts. It rejects missing, duplicate, corrupt or unexpected production JARs
before either publisher runs. GitHub receives only the verified ten-JAR bundle,
its manifest and SHA-256 checksums; CurseForge retains the same project and uses
loader-specific relations (Forge 1.12.2 does not require Architectury).

Publication does not convert incomplete runtime evidence into a pass: the
1.16.5 dedicated-client eligibility restriction and Fabric 26.2 historical plain
renderer calibration remain separately recorded in the requested-generation workflow.
No account eligibility override or acceptance-gate weakening is introduced.
