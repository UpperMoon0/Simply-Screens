# Simply Screens — server API and operator commands

Simply Screens exposes `com.nstut.simplyscreens.api.ScreenApi` for server-side
mod integrations on Fabric/Forge 1.20.1, Fabric/NeoForge 1.21.1 and NeoForge 26.1.2.
The shared `ScreenEvents` class reports logical state changes, not one event per
block in a linked multiblock display.

## Server API

The public API accepts an actual `MinecraftServer`, an optional **online**
`ServerPlayer` and a stable content key. Create named keys with
`ScreenApi.networkKey("station")`; physical display IDs have keys of the form
`display/<uuid>`. Methods must run on the **Minecraft server thread** and return
false, null or an empty immutable list on a missing/off-thread/unloaded target.
Use your platform's server scheduler to marshal calls from other threads.
The API never force-loads chunks or scans the world.
Linked displays use the network's live access policy, including after ownership
transfers. A `display/<uuid>` content key is active only for an unlinked display
or an enabled local override; disabled overrides are omitted from discovery and
reject reads and mutations. Use the linked network key for their current content.

~~~java
String key = ScreenApi.networkKey("station");
var current = ScreenApi.status(server, player, key);
if (current != null && current.canControl()) {
    boolean changed = ScreenApi.selectImage(server, player, key, imageUuid);
}
var networks = ScreenApi.listNetworks(server, player);
var accessibleImages = ScreenApi.listImages(server, player);
boolean started = ScreenApi.play(server, player, key, player.getUUID(), savedPlaylistId);
boolean paused = ScreenApi.transport(server, player, key, "PAUSE");
var display = ScreenApi.display(server, player, "minecraft:overworld", new BlockPos(100, 70, 200));
~~~

Transport actions: `PAUSE`, `RESUME`, `STOP`, `NEXT`, `PREVIOUS`, `RESTART`.
`play` accepts the owner of a saved playlist and its UUID. It does **not**
allow a player to play another user's private playlist; operators retain the
server's existing administrative media permissions. Failed operations have no
success side effects. Names and image UUIDs are not guessed or auto-created.

The caller's permissions are always determined from the server's live player:
- `null` represents anonymous automation, never an operator. It can only
  control an explicitly `PUBLIC` network, select ownerless/legacy public assets,
  or clear an image; private libraries remain inaccessible.
- Nonoperators may inspect `VIEW` networks, mutate only `CONTROL` networks,
  and select media they own (or legacy public media). TRUSTED policies use
  configured trusted-player UUIDs.
- `MANAGE` owner identity is only included in status when the actor may manage
  that network. For CONTROL-only callers without VIEW, the selected image and
  playback details are redacted.
- `ScreenApi.listImages` only returns authorized image IDs/names. It does not
  enumerate other players' unused private media.

`ScreenApi.display` uses the specified dimension and checks only already loaded
chunks; null means missing, unloaded or inaccessible. Use the content key
from this snapshot to manage network playback.

## Events

~~~java
AutoCloseable registration = ScreenEvents.listen(event -> {
    if (event.type() == ScreenEvents.Type.IMAGE_CHANGED) {
        // event.key() identifies a network or standalone display.
    }
});
// On mod unload / when replacing a listener:
registration.close();
~~~

Event types: `IMAGE_CHANGED`, `SLIDESHOW_STARTED`, `SLIDESHOW_PAUSED`,
`SLIDESHOW_RESUMED`, `SLIDESHOW_STOPPED`, `SLIDE_ADVANCED`,
`SLIDESHOW_COMPLETED`. Listener callbacks run at the **end of a server tick**,
outside persistence locks. A failing subscriber cannot break later subscribers.
Changes to one logical network emit once regardless of the number of linked
blocks. The change record contains only current displayed-image metadata,
logical slide index and revision; no private unused gallery information.
Every successful `play` creates a fresh running snapshot and emits exactly one
`SLIDESHOW_STARTED`, including replacement with the same saved playlist. It does
not also emit a resume or slide-advance event. Transport `RESTART` rewinds the
current slide; it emits a lifecycle event only if playback starts from STOPPED.

Server integrations should unregister listeners when no longer needed.
Pending changes from the previous world are dropped on server shutdown/start.

## Operator commands

Commands require OP level 2 (or server console), use the same
server-authoritative services, and do not force-load chunks:

| Command | Result |
|---|---|
| `/screens list [page]` | Paginated registered named networks and display keys |
| `/screens info <screenId>` | Network owner, access, selected image and playback status |
| `/screens status <screenId>` | Alias of info |
| `/screens set <screenId> <imageUUID>` | Select validated stored media, cancel slideshow |
| `/screens clear <screenId>` | Blank a network and cancel slideshow |
| `/screens play <screenId> <ownerUUID> <playlistUUID>` | Start a saved playlist |
| `/screens pause|resume|stop|next|previous|restart <screenId>` | Slideshow transport |
| `/screens media [page]` | Paginated stored media UUIDs (admin only) |
| `/screens display <dimension> <x> <y> <z>` | Inspect a **loaded** screen tile and anchor metadata |
| `/screens reconcile <screenId>` | Refresh loaded display state from persisted selection |

`/simplyscreens` is an equivalent alias. Network IDs and typed standalone `display/<uuid>` targets support tab completion
and quoted identifiers; use `/screens list` to find the right target.
Example: `/screens display minecraft:the_nether 10 70 -42`.

All successful modifications are logged with the initiating source.
Invalid UUIDs, absent keys, wrong permissions, missing images and unloaded
chunks give explicit failures instead of silently creating arbitrary networks.

## Compatibility

World metadata and Screen IDs are unchanged; the existing `screen_networks.json`,
`screen_playlists.dat` and image gallery remain authoritative.
Image selection cancels the active slideshow; playlist mutations synchronize
loaded displays, while unloaded displays reconcile on reload.
The API is not a client packet protocol and must not be exposed to untrusted
external calls without independent authorization.
