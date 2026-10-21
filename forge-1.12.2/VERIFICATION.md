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

Not established by these checks: a complete ForgeGradle packaging run, actual
client/server discovery and join, screenshots, image-rendering correctness or
shader compatibility. Native `runLiveJoinTestServer` / `runLiveJoinTestClient`
and the maintained UI/personal-settings probes are available for the separate
acceptance workflow. Build products, downloaded tools and Minecraft binaries
are deliberately excluded from source control.
