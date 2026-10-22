# Forge 1.12.2 verification

Verified locally on 2026-10-21:

- All 101 production Java sources compile with Jabel / JDK 17 `--release 8`.
- The API classpath is genuine Minecraft 1.12.2 plus Forge 14.23.5.2864 patches,
  mapped to stable MCP39. It is not a substitute stub API. The exact official
  MCP merge tool and Forge binary patcher produced the verification classpath.
- All 180 shared and native tests pass on Java 8, with zero skipped tests.
  Native cases cover image color/transparency, bounded Unicode buffers, native
  directional message envelopes, buffer ownership and quoted command arguments.
- Fourteen generator/build regression checks pass, including authoritative
  permission checks, non-loading lookups, native tile position/NBT ordering,
  real Forge dependency remapping, resources and preserved acceptance probes.
- The assembled diagnostic payload passes the repository's native identity,
  Java 8 bytecode, gameplay-class completeness and external-dependency ownership
  checks. It is not the reobfuscated distribution artifact.

The initial direct checks above did not establish ForgeGradle packaging or
actual client/server discovery and join. These subsequently passed at
`bb44c3726adaea17b729b5baa635057f923dad11` in
[run 39622599645](https://github.com/UpperMoon0/Simply-Screens/actions/runs/39622599645):
real dedicated join, OpenUI tab/resize smoke, and native wrapper exit zero.
Native model-parser tests and the production jar gate additionally verify all
18 variant selectors resolve to packaged models after the native block/ prefix.
Ignored legacy ASM scan warnings and unavailable headless audio/narrator remain
in the logs, so this is not warning-free loading. Screenshots, image-rendering
correctness and shader compatibility remain separate and unproven. Native `runLiveJoinTestServer` / `runLiveJoinTestClient`
and the maintained UI/personal-settings probes are available for the separate
acceptance workflow. Build products, downloaded tools and Minecraft binaries
are deliberately excluded from source control.
