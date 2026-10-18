# Simply Screens

A Minecraft mod that adds configurable screen blocks capable of displaying images from local files or the web, supporting dynamic multi-block structures and synchronized screen networks.

The client interface is powered by **OpenUI MC 0.0.8 or newer**, which is a required client-side dependency matching your Minecraft version and loader. On Fabric, the OpenUI dependency is declared for both sides because Fabric metadata cannot scope dependencies to the client, so a dedicated server also needs the OpenUI mod installed to satisfy metadata checks even though Simply Screens' server logic never uses it.

[![CurseForge](https://cf.way2muchnoise.eu/simply-screens.svg)](https://www.curseforge.com/minecraft/mc-mods/simply-screens)

## Sable compatibility

Minecraft 1.21.1 builds support optional **Sable 2.0.5 and 2.0.6** on Fabric and NeoForge. The dependency range allows later 2.x releases using the same Companion 1.6.0 API; those future releases are not yet runtime-verified. Sable 3 requires a new compatibility review. Sable is not required for ordinary screens.

## Features

- **Screen Blocks:** Display customizable images in-game on wall, floor, or ceiling mounts.
- **Dynamic Multi-Block Scaling:** Combine multiple screen blocks in any rectangle (up to 64x64) — the display automatically adapts and spans across all blocks.
- **Synchronized Screen Networks:** Group screens using a **Screen ID** to display identical media in real-time across your server.
- **Aspect Ratio Control:** Choose between full stretching or aspect-ratio preservation.
- **In-Game Media Management:**
  - Upload PNG, JPG, and JPEG images directly from your computer via an in-game file dialog.
  - Download images from web URLs with built-in SSRF and security filtering.
  - Browse, select, and manage server-stored images in the screen GUI.
- **OpenUI Interface:** Responsive gallery, keyboard-friendly controls, removal confirmation, and a persisted live light/dark theme.
- **Security & Hardening:**
  - Server-authoritative distance, permission, and ownership validation.
  - DNS/IP-level SSRF filtering against private, loopback, or cloud-metadata destinations.
  - Per-player upload concurrency and rate limits, bounded processing queues, and full image decode checks.
  - Atomic registry operations with automated `.bak` backups and crash recovery.

## Installation and upgrade to 0.8.13

Install the artifact matching **both** your Minecraft version and loader. The following mods are required:

- **PacketWeave 0.1.1 or newer**, matching the same Minecraft version and loader, on the client **and** dedicated server.
- **Architectury API**, matching the same Minecraft version and loader, on both sides.
- **Fabric API** on Fabric.
- **OpenUI MC 0.0.8 or newer** on clients; Fabric dedicated servers also need it to satisfy Fabric dependency metadata.

Download PacketWeave from its [release assets](https://github.com/UpperMoon0/PacketWeave/releases) or its approved [CurseForge files](https://www.curseforge.com/minecraft/mc-mods/packetweave/files). Do not install the development-only API/core jar as a substitute for the native loader jar. Missing PacketWeave or a version below 0.1.1 must be resolved before the loader can start Screens.

When upgrading from 0.8.9, add PacketWeave to every client and server before launching. Keep the existing world, `simply_screens_images` directory, screen registry, and configuration. This update does not require a media-file or world migration, and keeps the existing native Screens packet format. Back up worlds before any mod upgrade.

## Shader compatibility in 0.8.11

Fully opaque screen image pixels retain their source RGB after Iris/Oculus shader
processing, while the original world image can still contribute surrounding bloom.
Shader mods are optional. Use the loader-specific Sodium/Embeddium, Iris/Oculus
and Extra combinations in the [tested shader matrix](tools/shader/README.md).
Complementary Reimagined is covered by real framebuffer tests on all five targets.

Semi-transparent and filtered edge pixels keep the shader's existing appearance
to avoid double blending. Packs that rewrite depth, warp geometry, or apply later
postprocessing can exceed this guarantee. The precise contract, empirical glow
controls and reproducible development setup are documented in the matrix above.
Upgrading from 0.8.10 needs no additional required mod or saved-data migration.

## Getting Started

1. Craft and place a **Screen** block.
2. Build larger displays by placing screen blocks adjacent to each other in a rectangle.
3. Right-click any block in the screen to open the management GUI.
4. Upload an image, download from a URL, or choose a saved image from the list.
5. (Optional) Set a **Screen ID** to link screens together.

## Development & Building

Simply Screens uses Architectury Loom for 1.20.1/1.21.1 and a standalone ModDevGradle target for NeoForge 26.1.2. Start with Java 21 and initialize pinned sources:

```bash
git submodule update --init --recursive
# Install the pinned OpenUI artifacts as documented in .github/actions/openui/action.yml.
./gradlew testAllVersions --no-daemon
./gradlew build --no-daemon
```

The `packetweave-api` project compiles the pinned public API for compile-time and unit-test use only. It is never shipped in Screens. Production and development game launches need the matching native PacketWeave mod. Build it with the target's wrapper in `vendor/PacketWeave/targets/<loader-version>` (Java 17 for Forge; Java 25 for PacketWeave's newer target builds), then put that jar in the game's `mods` directory. For a Gradle development launch, alternatively pass `-PpacketweaveRuntimeJar=/absolute/path/to/the-native.jar`; do not also install the same jar in `mods`.

See [TESTING.md](TESTING.md) for source/JVM contracts, missing/incompatible dependency checks, real client/server joins, and visual evidence.

### Supported Minecraft Versions

- **1.20.1** (Fabric & Forge)
- **1.21.1** (Fabric & NeoForge)
- **26.1.2** (NeoForge)

## License

This project is distributed under the MIT license.

## Media transport

Screens calls the **standalone PacketWeave** public API for chunk splitting and bounded upload/download reassembly on all five targets. Screens jars contain neither a public nor a relocated copy of that API. This avoids split packages and gives every consuming mod one runtime library. Screens retains native packet registration, screen permissions, image validation, metadata, and storage policy.

Clone with `git clone --recurse-submodules`, or initialize the submodule before building. The immutable source pin supplies a compile-only API without relying on unpublished Maven artifacts. The pinned version, loader minimum, and CurseForge required dependency must remain aligned; release PacketWeave's matching native artifacts before releasing Screens.

Packets remain wire-compatible. Chunk arrays are decoded with a 30 KiB cap; receivers enforce byte and session limits, reject duplicate/changed envelopes, and release state on expiry or disconnect. Uploads stay bound to the authenticated sender, world, and original screen position. Download workers wait for game-thread dispatch of each chunk to avoid filling the server task queue with an entire file. This is local dispatch backpressure, not remote delivery acknowledgement.

## Configuration

Edit `config/simply_screens.properties`; server limits remain authoritative. Names and defaults below match `Config.java`:

| Key | Default | Meaning |
|---|---:|---|
| `viewDistance` | `64` | View distance; clamped to 8–512 |
| `screenTickRate` | `100` | Structure check interval in ticks; clamped to 1–72,000 |
| `disableUpload` | `false` | Disable uploads from client files |
| `disableUrlDownload` | `false` | Disable server image imports from URLs |
| `debugRendering` | `false` | Enable rendering diagnostics |
| `maxUploadSize` | `5242880` | Upload bytes; 5 MiB |
| `maxUrlDownloadSize` | `10485760` | URL import bytes; 10 MiB |
| `maxImagesPerPlayer` | `256` | Stored images per player; clamped to 1–1,024 |
| `maxStoragePerPlayer` | `536870912` | Stored bytes per player; 512 MiB |
| `maxStorageTotal` | `2147483648` | Total stored bytes; 2 GiB |

Both transfer-size limits are clamped to 1 KiB–100 MiB. The properties are `disableUrlDownload` and `maxUrlDownloadSize`; `disableDownload` and `maxDownloadSize` are not read by this version.

### OpenUI on clients and dedicated servers

OpenUI MC is required on clients only. Dedicated servers can run without it.
On Fabric, the client requirement is checked before client initialization; missing or
incompatible OpenUI versions produce an installation error before the UI loads.
Forge and NeoForge declare the dependency with `side="CLIENT"`.
