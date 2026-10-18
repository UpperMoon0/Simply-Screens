# Simply Screens

Simply Screens adds configurable screen blocks that display images from your local computer or the web in Minecraft. Build multi-block displays of any size, create synchronized screen networks across your world, and easily manage your media in game.

## Features

- **Screen Block:** Select an image, download from the web, or upload from your computer to display on customizable screen blocks.
- **Dynamic Multi-Block Displays:** Place multiple screen blocks together in a rectangular shape (up to 64x64 blocks). The screen automatically connects, detects its structure, and stretches or scales the image across all connected blocks.
- **Synchronized Screen Networks:** Link multiple screens across your world using a shared **Screen ID** so all connected screens display the same image simultaneously.
- **Aspect Ratio Control:** Toggle between stretching the image to fill the screen or maintaining its original aspect ratio.
- **Ownership and Access:** Own displays and networks, trust players, transfer ownership, and choose separate view/control policies. Manage these settings in the existing Settings tab.
- **Local Image Override:** Show different content on one linked display without changing the network. Aspect ratio remains local to each display.
- **Local File Uploads:** Upload PNG, JPG, and JPEG images directly from your computer via an in-game file picker.
- **Safe Web Downloads:** Download images directly from URLs with built-in security protections.
- **Multiplayer Synchronized:** Fully server-authoritative and dedicated-multiplayer ready with client-side synchronization and robust cross-chunk structure recovery.

## Getting Started

1. Craft and place a **Screen** block on any wall, floor, or ceiling.
2. Expand the screen by placing additional screen blocks adjacent to it in a rectangle.
3. Right-click the screen to open its interface.
4. Upload an image from your computer, paste an image URL to download, or select an existing uploaded image from the list.
5. *(Optional)* Enter a **Screen ID** and click **Link ID** to sync this screen with other screens using the same ID.
6. Toggle **Aspect Ratio** according to your layout preference.

## Supported Platforms

- Fabric 1.20.1
- Forge 1.20.1
- Fabric 1.21.1
- NeoForge 1.21.1
- NeoForge 26.1.2

## Required Dependencies

- **PacketWeave 0.1.1 or newer**, matching your Minecraft version and loader, on clients and dedicated servers
- **Architectury API** matching your Minecraft version and loader
- **Fabric API** on Fabric
- **OpenUI MC 0.0.8 or newer** matching your Minecraft version and loader (clients only)

## Upgrading to 0.9.0

Add the matching native PacketWeave jar to every client and server. Screens uses its public transfer API and does not bundle a second copy. Back up the world before upgrading. Existing media and Screen IDs are preserved; the registry is automatically imported into a versioned network store. Legacy unowned displays/networks require an operator claim in Settings before editing. Private unused images cannot be downloaded merely by knowing their UUID; viewing a permitted tracked display remains supported. Install matching Screens versions on clients and servers. The loader requires PacketWeave 0.1.1 or newer, and release files declare it as a required CurseForge dependency. See the [access and recovery guide](https://github.com/UpperMoon0/Simply-Screens/blob/main/docs/network-access.md) for policy and backup details.

## Shader image colors

Version 0.8.11 preserves fully opaque image colors on the Iris/Oculus path while
retaining surrounding shader bloom. Complementary is tested with matching
Sodium/Embeddium and Extra stacks across the five supported targets. Shader mods
remain optional; do not mix Fabric, Forge and NeoForge variants.

Semi-transparent/filtered edges remain shader-processed. Arbitrary depth changes,
geometric warps and later postprocessing are outside the guarantee. See the
[compatibility matrix and limits](https://github.com/UpperMoon0/Simply-Screens/blob/main/tools/shader/README.md).
Existing 0.8.10 installations need no new required dependency or data migration.

## Configuration

Settings are in `config/simply_screens.properties`. Important defaults:

- `disableUpload=false`: allow in-game client uploads
- `disableUrlDownload=false`: allow image imports from URLs
- `maxUploadSize=5242880`: 5 MiB per upload
- `maxUrlDownloadSize=10485760`: 10 MiB per URL import
- `maxImagesPerPlayer=256`: stored image count per player
- `maxStoragePerPlayer=536870912`: 512 MiB per player
- `maxStorageTotal=2147483648`: 2 GiB total storage
- `screenTickRate=100`: structure check interval in ticks
- `viewDistance=64`: rendering view distance
- `debugRendering=false`: rendering diagnostics

Server limits are authoritative. `disableDownload` and `maxDownloadSize` are not valid keys; use the URL-specific names above. See the [README configuration reference](https://github.com/UpperMoon0/Simply-Screens#configuration) for bounds.

## Support

- [Join the Discord community](https://discord.gg/4vD9WuT2As)
- [Report bugs on GitHub](https://github.com/UpperMoon0/Simply-Screens/issues)
