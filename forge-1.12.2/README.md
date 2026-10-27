# Simply Screens on Forge 1.12.2

This native target uses Forge 14.23.5.2864, stable MCP 39, and Java 8.
ForgeGradle 3 runs under Java 8; the owned-source backport compiler uses Java 17
with Jabel and `--release 8`. No Minecraft code or binaries are included here.

The maintained shared services and 1.20.1 gameplay implementation are generated
at build time. Ownership, server-authoritative permissions, transfers, quotas,
URL filtering, persistence, playlists, commands and the public API use the same
implementation. Native adapters supply Forge events, packet transport, tile
lifecycle, rendering, file selection and OpenUI's 1.12.2 graphics/text classes.

Build the pinned requested-generation branches of PacketWeave and OpenUI first. See the root requested-generations guide for their exact source pins.
Their native jars are development dependencies, not publicly released versions.
OpenUI is required by clients; its inert native container is also included in
the development dedicated-server acceptance classpath. PacketWeave is required
on both sides.
Architectury is not required for this target.

```sh
export JAVA_HOME=/path/to/jdk8
export JAVA17_HOME=/path/to/jdk17
./gradlew build --no-daemon \
  -PpacketweaveJar=/path/to/packetweave-forge-1.12.2-0.1.5.jar \
  -PopenuiJar=/path/to/openui-mc-forge-1.12.2-0.0.15.jar
```

`build` runs the shared contract tests on Java 8 and the native adapter tests,
then reobfuscates the production jar. The result is
`build/libs/simply_screens-forge-1.12.2-0.10.1.jar`.

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
