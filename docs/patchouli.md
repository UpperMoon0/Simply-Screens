# Optional Patchouli guide

Install Patchouli for the same Minecraft version and loader on the server and clients. The Simply Screens Guide appears in the Screens creative tab. Craft another copy with one book and one amethyst shard, placed side by side in that order. Without Patchouli, Screens works normally and the guide recipe is skipped.

The guide covers setup, image imports, the gallery, linked Screen IDs, aspect ratio, local image overrides, viewing/control permissions, trusted players, ownership transfer, privacy, and troubleshooting. The guide currently covers static images; playlist controls are documented separately in the main user guide.

## Development

Native development client/server runs load the pinned matching Patchouli runtime by default through `screensPatchouli=true` in `gradle.properties`. Use `-PscreensPatchouli=false` to test without it. The integration has no Java references to Patchouli, no bundled jar, and no required Patchouli dependency. Recipes use native loader conditions evaluated before parsing, including the custom 1.20.1 book serializer.

Book definitions must be in the physical loader mod root for discovery; localized contents and item textures come from the shared resource pack. Patchouli 1.20.1 uses a full item model path, 1.21.1 prefixes it internally, and 26.1.2 uses an item model definition. All three formats are supplied.

Format references: [Patchouli book definitions](https://vazkiimods.github.io/Patchouli/docs/reference/book-json/), [Forge conditions](https://docs.minecraftforge.net/en/1.20.1/resources/server/conditional/), [NeoForge conditions](https://docs.neoforged.net/docs/1.21.1/resources/server/conditions/), and Fabric Resource Conditions API.

Run `python -m unittest discover -s tools -p 'test_patchouli_resources.py'` for source resource validation. After building each target, run `python tools/check_patchouli_jar.py --module <target>` to check physical book discovery, loader-specific recipes and models, optional metadata, content, and the 64x64 RGBA texture in the production jar. Page text has a conservative length budget; this is not proof of rendering with every resource-pack font.
