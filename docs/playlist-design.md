# Named image playlists (issue #15)

Named slide playlists in v0.10.0 build on the v0.9.0 screen-network and access model.

## Experience

Follow Simply Speakers' current playlist flow: gallery item actions add slides or queue them; a searchable saved-playlist selector changes only the editor; explicit Play selects the playback source; compact transport remains visible across tabs. Named playlists support create, rename, duplicate, delete, per-slide duration and reorder. A separate queue distinguishes one-off requests from saved slides. Announcement interrupts temporarily and resumes the exact suspended position.

## State and security

Use an independent server-owned timeline keyed by the authoritative content key, preserving Screen ID synchronization and local overrides. Active server ticks advance the timeline even when every linked chunk is unloaded; paused single-player time and server downtime do not consume a slide. The editor itself does not pause the world. Persist offsets and ordering. Saved edits do not rewrite a running snapshot. Playlist library mutations are scoped to the actor; playback requires current screen control permission and referenced images must be in the actor's authorized library. Wire messages and resource usage are bounded. Private image downloading continues to require current screen visibility and chunk tracking.

The playlist toolbar's Play always loads the selected saved snapshot. The persistent player pauses/resumes active playback; when stopped it starts queued slides first, or the selected saved list if the queue is empty. Creating a playlist from an image's Add to playlist menu also adds that image after the creation acknowledgement. Compact layouts retain playback status and repeat/shuffle state. Playlist names are truncated within their controls, with the full name available on hover.

## Required verification gates

- Shared timeline/store tests: CRUD/reorder, repeat/shuffle, queue, announcement resume, persisted offset, deletion and corruption recovery.
- Native security/codec/UI tests for 1.20.1, 1.21.1 and 26.1.2.
- All five loader builds and live clients, synchronized distant displays and late join/chunk reload/restart evidence.
- Existing access/media/rendering regressions and independent review on final head.

Issue acceptance requires all gates on the exact candidate head; unit tests and ordinary join smoke alone do not establish live playlist correctness.
