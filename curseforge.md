# Simply Screens

Simply Screens adds configurable screen blocks that display images from your local computer or the web in Minecraft. Build multi-block displays of any size, create synchronized screen networks across your world, and easily manage your media in game.

## Features

- **Screen Block:** Select an image, download from the web, or upload from your computer to display on customizable screen blocks.
- **Dynamic Multi-Block Displays:** Place multiple screen blocks together in a rectangular shape (up to 64x64 blocks). The screen automatically connects, detects its structure, and stretches or scales the image across all connected blocks.
- **Synchronized Screen Networks:** Link multiple screens across your world using a shared **Screen ID** so all connected screens display the same image simultaneously.
- **Aspect Ratio Control:** Toggle between stretching the image to fill the screen or maintaining its original aspect ratio.
- **Local File Uploads:** Upload PNG, JPG, JPEG, GIF, WebP, and BMP images directly from your computer via an in-game file picker.
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

- **Architectury API** matching your Minecraft version and loader
- **Fabric API** on Fabric

## Configuration

- `disableUpload`: Disable in-game uploads from client computers
- `disableDownload`: Disable downloading images from URLs
- `maxUploadSize`: Maximum upload size in bytes (default: 10 MB)
- `maxDownloadSize`: Maximum URL download size in bytes (default: 10 MB)
- `screenTickRate`: Rate (in ticks) at which screens check and update structure integrity

## Support

- [Join the Discord community](https://discord.gg/4vD9WuT2As)
- [Report bugs on GitHub](https://github.com/UpperMoon0/Simply-Screens/issues)
