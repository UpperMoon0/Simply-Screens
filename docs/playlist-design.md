# Named image playlists (issue #15)

Named slide playlists in v0.9.1 build on the v0.9.0 screen-network and access model.

## Experience

Gallery actions add images to saved slide playlists or to temporary slides in Up next. A searchable selector changes only the editor; Start displays a saved slideshow. A centered single-row icon toolbar remains visible across tabs; elapsed slide time appears beside the displayed image in the header. Loop off/all/current and saved/random order use distinct mode textures with hover tooltips. Named playlists support create, rename, duplicate, delete, per-slide duration and drag reordering.

Up next distinguishes temporary images from saved slides. It omits the repeated tab heading, hides Clear while empty, and does not expose image-prefetch hints as an exact next-slide list. Show now displays an image for 10 seconds, then returns to the exact interrupted slide, timer and paused state. Return to slideshow ends that temporary image early.

Use display terminology in the interface: Displaying, Start slideshow, Show next, Loop and Saved/Random slide order. Internal protocol action names retain their existing identifiers.

## State and security

Use an independent server-owned timeline keyed by the authoritative content key, preserving Screen ID synchronization and local overrides. Active server ticks advance the timeline even when every linked chunk is unloaded; paused single-player time and server downtime do not consume a slide. The editor itself does not pause the world. Persist offsets and ordering. Saved edits do not rewrite a running snapshot. Playlist library mutations are scoped to the actor; playback requires current screen control permission and referenced images must be in the actor's authorized library. Wire messages and resource usage are bounded. Private image downloading continues to require current screen visibility and chunk tracking.

The playlist toolbar's Start always loads the selected saved snapshot. The persistent slideshow controls pause or resume the current slideshow. When stopped, they start temporary slides first, or the selected saved list if no temporary slides remain. Creating a playlist from an image's Add to playlist menu also adds that image after the creation acknowledgement. Compact layouts retain playback status and loop and slide-order state. Playlist names are truncated within their controls, with the full name available on hover.

## Required verification gates

- Shared timeline/store tests: CRUD/reorder, repeat/shuffle, queue, announcement resume, persisted offset, deletion and corruption recovery.
- Native security/codec/UI tests for 1.20.1, 1.21.1 and 26.1.2.
- All five loader builds and live clients, synchronized distant displays and late join/chunk reload/restart evidence.
- Existing access/media/rendering regressions and independent review on final head.

Issue acceptance requires all gates on the exact candidate head; unit tests and ordinary join smoke alone do not establish live playlist correctness.
