# Simply Screens on Forge 1.12.2

This native target uses the Forge release and MCP mappings configured in its
build files. The game and tests run on the legacy Java runtime, while the
owned-source backport compiler uses a separate JDK with Jabel and the target
runtime API compatibility level. No Minecraft code or binaries are included here.

The maintained shared services and 1.20.1 gameplay implementation are generated
at build time. Ownership, server-authoritative permissions, transfers, quotas,
URL filtering, persistence, playlists, commands and the public API use the same
implementation. Native adapters supply Forge events, packet transport, tile
lifecycle, rendering, file selection and OpenUI's 1.12.2 graphics/text classes.

Build the native PacketWeave and OpenUI dependencies at the source pins recorded
in [`tools/requested-dependencies.lock.json`](../tools/requested-dependencies.lock.json).
Follow the [native-port guide](../docs/requested-generations.md) for the required
build steps. Development artifacts are not substitutes for published compatible
runtime jars.
OpenUI is required by clients; its inert native container is also included in
the development dedicated-server acceptance classpath. PacketWeave is required
on both sides.
Architectury is not required for this target.

```sh
export JAVA_HOME=/path/to/legacy-runtime-jdk
export JAVA17_HOME=/path/to/backport-compiler-jdk
./gradlew build --no-daemon \
  -PpacketweaveJar=/absolute/path/to/matching-native-packetweave.jar \
  -PopenuiJar=/absolute/path/to/matching-native-openui.jar
```

`build` runs the shared contract tests on Java 8 and the native adapter tests,
then reobfuscates the production jar. The resulting filename is derived from
the current `mod_version` in the root `gradle.properties` (under `build/libs/`).

The optional Patchouli guide uses a book and quartz because amethyst does not
exist in 1.12.2. The six facing directions are stored in block metadata; visual
anchor/child/error state is derived from the persisted tile identity.

The API uses native `EntityPlayerMP`, `WorldServer` and `BlockPos` types.
`/screens` and `/simplyscreens` expose the same operator controls, including
quoted Screen IDs and explicit loaded-dimension display inspection. Dimension
names are `minecraft:overworld`, `minecraft:the_nether`, `minecraft:the_end`, and
`forge:dimension_<id>` for additional dimensions. Inspection does not load chunks.

The image renderer retains per-tile coverage, aspect fitting, full brightness,
and the historical horizontal UV flip. The Iris/Oculus postprocessing contract
for newer versions is not an OptiFine 1.12.2 compatibility claim.

## Opt-in native acceptance runs

`runLiveJoinTestServer` and `runLiveJoinTestClient` use isolated
`run/live-join/server` and `run/live-join/client` directories. The shared
`tools/requested_live_join.py` runner prepares the isolated launch; ForgeGradle
discovers pinned native dependencies on the correctly remapped classpath.
The client joins localhost:25575, mounts the real OpenUI screen, changes tabs,
resizes it, closes it, then emits the pass marker and shuts down cleanly.
`-Dsimplyscreens.personalSettingsTest=true` additionally exposes the maintained
8-case native input, hover, persistence, reopening and screenshot probe through
LWJGL2. These are opt-in acceptance hooks, not claims of an executed GUI run.
