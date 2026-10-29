# Native acceptance history (immutable checkpoints)

This file preserves previously recorded, **commit-specific** validation and native
launch investigations. Versions, dates, source hashes, and exact runner details are
intentionally historical. They do **not** establish passing CI or compatibility
for a newer commit. For current requirements, use
[the evergreen native-port guide](requested-generations.md), the
[dependency lockfile](../tools/requested-dependencies.lock.json), and the
latest exact-head CI receipts.

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


## Sable compatibility checkpoint

The earlier optional Sable metadata review checked Sable 2.0.5 and stable
2.0.6 on the supported Minecraft 1.21.1 loaders. The range admitted those
releases and later 2.x examples, rejected older releases and Sable 3, and
retained optional loading.

The Sable tags `mc1.21.1-2.0.5-neoforge` and
`mc1.21.1-2.0.6-neoforge` used the same Companion 1.6.0 dependency.
At inspected upstream main commit
`227077f7f528c9beab1336a9fad2e27b754e4e70`, the relevant
`ActiveSableCompanion` and `ClientSubLevel` implementations and the
Companion dependency remained unchanged. This is historical metadata/source
evidence, **not** proof of future release compatibility or a complete All of
Create Aeronautics 2.8 playtest. Current compatibility must come from loader
metadata and the selected release's qualification results.
