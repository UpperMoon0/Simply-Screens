# Native API references

The port's API shape was checked against the user-provided, pinned source tree:

- Minecraft 1.12.2 / stable MCP39, commit `01087a3908d6e14c43de4ecc39cf1d4f5025a8bb`:
  https://github.com/UpperMoon0/MC-Modding-Src/tree/01087a3908d6e14c43de4ecc39cf1d4f5025a8bb/1.12.2
- Loaded tile lookup and tile world/position ordering:
  https://github.com/UpperMoon0/MC-Modding-Src/blob/01087a3908d6e14c43de4ecc39cf1d4f5025a8bb/1.12.2/net/minecraft/world/chunk/Chunk.java
- Forge dimension lookup: `MinecraftServer.getWorld` initializes absent dimensions,
  so non-loading reconciliation uses `DimensionManager.getWorld` instead:
  https://github.com/MinecraftForge/MinecraftForge/blob/1.12.x/src/main/java/net/minecraftforge/common/DimensionManager.java
- Native Forge full-brightness state restoration fields:
  https://github.com/MinecraftForge/MinecraftForge/blob/1.12.x/patches/minecraft/net/minecraft/client/renderer/OpenGlHelper.java.patch

The generator only processes Simply Screens-owned code. Minecraft's source,
mapped game jars, downloaded toolchains and generated build products are not
committed or bundled into the mod. Forge patches are supplied by ForgeGradle,
not imitated by compile-time stubs.
