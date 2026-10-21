# Simply Screens

A Minecraft mod that adds configurable screen blocks capable of displaying images from local files or the web, supporting dynamic multi-block structures and synchronized screen networks.

The client interface is powered by **OpenUI MC 0.0.14 or newer**, which is a required client-side dependency matching your Minecraft version and loader. Dedicated servers do not require OpenUI; Fabric checks the dependency during client startup.

[![CurseForge](https://cf.way2muchnoise.eu/simply-screens.svg)](https://www.curseforge.com/minecraft/mc-mods/simply-screens)

## Sable compatibility

Minecraft 1.21.1 builds support optional **Sable 2.0.5 and 2.0.6** on Fabric and NeoForge. The dependency range allows later 2.x releases using the same Companion 1.6.0 API; those future releases are not yet runtime-verified. Sable 3 requires a new compatibility review. Sable is not required for ordinary screens.

## Features

- **Screen Blocks:** Display customizable images in-game on wall, floor, or ceiling mounts.
- **Dynamic Multi-Block Scaling:** Combine multiple screen blocks in any rectangle (up to 64x64) — the display automatically adapts and spans across all blocks.
- **Synchronized Screen Networks:** Group screens using a **Screen ID** to display identical media in real-time across your server.
- **Aspect Ratio Control:** Choose between full stretching or aspect-ratio preservation.
- **Ownership and Access:** Own standalone displays and networks, trust players, transfer ownership, and configure separate viewing/content-control policies in Settings. Management and relinking stay with owners and operators.
- **Server-Side Screen API (0.10.0):** Actor-aware Java API for network discovery, image selection and slideshow transport, plus logical image/playback events. See [integration documentation](docs/screen-api.md).
- **Operator Commands (0.10.0):** Use `/screens` or `/simplyscreens` for networks, images, playback controls, multi-dimension loaded-display inspection, and troubleshooting.
- **Local Image Overrides:** Let an individual linked display show different content while keeping its aspect ratio and the rest of the network independent.
- **In-Game Media Management:**
  - Upload PNG, JPG, and JPEG images directly from your computer via an in-game file dialog.
  - Download images from web URLs with built-in SSRF and security filtering.
  - Browse, select, and manage server-stored images in the screen GUI.
- **Optional In-Game Guide:** Install Patchouli to get a Simply Screens guide in the creative tab, or craft it with a book and an amethyst shard. Covers images, links, display settings, and access. Screens also works without Patchouli.
- **OpenUI Interface:** Responsive gallery, keyboard-friendly controls, removal confirmation, and a persisted live light/dark theme.
- **Security & Hardening:**
  - Server-authoritative distance, permission, and ownership validation.
  - DNS/IP-level SSRF filtering against private, loopback, or cloud-metadata destinations.
  - Per-player upload concurrency and rate limits, bounded processing queues, and full image decode checks.
  - Atomic registry operations with automated `.bak` backups and crash recovery.

## Installation and upgrade to 0.10.0

Install the artifact matching **both** your Minecraft version and loader. The following mods are required:

- **PacketWeave 0.1.1 or newer**, matching the same Minecraft version and loader, on the client **and** dedicated server.
- **Architectury API**, matching the same Minecraft version and loader, on both sides.
- **Fabric API** on Fabric.
- **OpenUI MC 0.0.14 or newer** on clients.

Download PacketWeave from its [release assets](https://github.com/UpperMoon0/PacketWeave/releases) or its approved [CurseForge files](https://www.curseforge.com/minecraft/mc-mods/packetweave/files). Do not install the development-only API/core jar as a substitute for the native loader jar. Missing PacketWeave or a version below 0.1.1 must be resolved before the loader can start Screens.

When upgrading from 0.8.9, add PacketWeave to every client and server before launching. Keep the existing world, `simply_screens_images` directory, screen registry, and configuration. Version 0.9.0 automatically imports existing registry images/owners into a versioned `screen_networks.json`; existing media files and Screen IDs are preserved. Legacy unowned networks and blank-ID displays require an operator claim before editing. New player-placed displays belong to their placer. Added access and playlist packets require matching client/server Screens versions. Version 0.9.1 stores personal playlists and active timelines in `screen_playlists.dat` with a validated backup; keep both with the world. 0.10.0 adds a server-side API and commands without changing saved data; no additional runtime mod is required. Back up worlds before upgrading; see [ownership, migration, and recovery](docs/network-access.md) before changing permissions or restoring data.

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

Patchouli is optional. Install the matching loader/version on the server and clients to use the guide; see [guide integration and development setup](docs/patchouli.md).

## Getting Started

1. Craft and place a **Screen** block.
2. Build larger displays by placing screen blocks adjacent to each other in a rectangle.
3. Right-click any block in the screen to open the management GUI.
4. Upload an image, download from a URL, or choose a saved image from the list.
5. (Optional) Set a **Screen ID** to link screens together.

## Development & Building

The requested **Forge 1.12.2, Fabric/Forge 1.16.5, and Fabric/NeoForge 26.2** ports are separate native projects. They require source-built **PacketWeave 0.1.4** and **OpenUI MC 0.0.15** dependencies while the dependency PRs are under review. See [native port builds and verification levels](docs/requested-generations.md). The existing release lanes and their installation requirements remain unchanged.

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

### Personal display settings

Open a screen and choose **Settings > Your display settings** to change screen render distance
(8-512 blocks). It applies immediately to all screens for you, saves on this device, and works
without screen-management permissions. Existing mod-config values are imported once. Server
visibility policies still apply. See [the settings audit](docs/settings.md) for all configuration options.

## Named slide playlists (0.9.1)

Open a screen and use a gallery image's **…** menu to add it to a saved playlist. In **Playlists**, choose or create a list, then select **Start** to display it. Drag slide rows to reorder them; each slide's menu changes its duration (1–86400 seconds) or removes it.

Slideshow controls stay visible across tabs: Previous/Next slide, Start/Pause/Resume/Stop slideshow, Restart current slide, Loop (off/all slides/current slide), and Slide order (saved/random). The header identifies the displayed image and elapsed slide time. The toolbar is a single row of buttons; distinct 64x64 mode icons show loop off/all/current and saved/random order, with hover tooltips explaining each mode. Saved edits and deletion leave the current slideshow unchanged; select **Start** to display your edits.

**Up next** lists temporary 10-second slides added with **Show next** or **Add temporary slide**. These appear before the next saved slide and do not change the saved playlist. **Show now** temporarily displays an image immediately, then returns to the interrupted slide, timer and paused state. During that image, **Return to slideshow** ends it early.

A removed image is removed from saved and running references. An image that cannot be decoded shows the existing error texture until its slide duration expires.

Linked screens use one authoritative content timeline, even with all their chunks unloaded. A local override has its own timeline. World saves preserve slide timers, temporary slides, loop settings and slide order; server downtime does not advance them. Normal autosave checkpoints are every five seconds, with immediate persistence on commands and clean shutdown. Selecting a static gallery image discards that display/network's previous slideshow. Ownership transfer discards resumable timelines belonging to that access group. Other access changes preserve playback but immediately govern future control and image downloads.

Playlists are private to their creator (64 lists, 256 slides each). Starting a slideshow or showing temporary images requires current screen control permission and image-library access; sharing a visible display never grants ownership of its media. Upcoming-image prefetch uses the same current tracking/view checks as display downloads and does not expose library names. Prefetch is capped at two images per viewer per two-second batch, each at most 8 MiB encoded. Client GPU cache is capped at 32 textures/128 MiB and a single decoded image at 16 megapixels.
