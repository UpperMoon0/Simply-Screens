# Simply Screens

A Minecraft mod that adds configurable screen blocks capable of displaying images from local files or the web, supporting dynamic multi-block structures and synchronized screen networks.

The client interface is powered by **OpenUI MC 0.0.8 or newer**, which is a required client-side dependency matching your Minecraft version and loader. On Fabric, the OpenUI dependency is declared for both sides because Fabric metadata cannot scope dependencies to the client, so a dedicated server also needs the OpenUI mod installed to satisfy metadata checks even though Simply Screens' server logic never uses it.

[![CurseForge](https://cf.way2muchnoise.eu/simply-screens.svg)](https://www.curseforge.com/minecraft/mc-mods/simply-screens)

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

## Getting Started

1. Craft and place a **Screen** block.
2. Build larger displays by placing screen blocks adjacent to each other in a rectangle.
3. Right-click any block in the screen to open the management GUI.
4. Upload an image, download from a URL, or choose a saved image from the list.
5. (Optional) Set a **Screen ID** to link screens together.

## Development & Building

Simply Screens uses Gradle with Architectury Loom:

```bash
# Build all subprojects
./gradlew build

# Run unit tests
./gradlew test
```

### Supported Minecraft Versions

- **1.20.1** (Fabric & Forge)
- **1.21.1** (Fabric & NeoForge)
- **26.1.2** (NeoForge)

## License

This project is distributed under the MIT license.
