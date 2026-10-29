# Simply Screens — NeoForge 26.1.2

This is the native NeoForge edition of Simply Screens. It shares gameplay and
media logic with the common modules and supplies NeoForge-specific startup,
networking, and rendering adapters. Loader and dependency versions are defined
by this module's build files, root `gradle.properties`, and mod metadata.

## Install and upgrade

Install the Screens jar matching NeoForge and Minecraft 26.1.2 on both clients
and dedicated servers. The matching native **PacketWeave** and **Architectury
API** mods are required on both sides; **OpenUI MC** is client-only. Check the
selected release's loader metadata and changelog for exact dependency minima,
and keep client and server Screens releases aligned as media packets can change.

Back up the world, images, network registry, configuration and playlists before
upgrading. See [installation and upgrades](../README.md) and
[ownership and recovery](../docs/network-access.md).

## Build and verify

From the repository root, with the target's configured JDK/toolchain:

```sh
git submodule update --init --recursive
./gradlew :neoforge-26.1.2:test --no-daemon
./gradlew :neoforge-26.1.2:build --no-daemon
```

Follow the root instructions for matching standalone native development
dependencies. The PacketWeave API/core test jar is not a substitute for the
native runtime mod. See [TESTING.md](../TESTING.md) for dependency-rejection
checks, real client/server join tests, visual/shader checks, and their
qualification limits. A successful compilation does not prove gameplay
behavior. Exact release-specific changes are in the [changelogs](../changelog/).
