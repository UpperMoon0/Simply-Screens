# Ownership, media access, and network state

Version 0.9.0 adds the foundation tracked by issues #11, #12 and #13 on all five supported loader/version combinations.

## Settings

Place a screen as a player to own the new standalone display. In **Settings**, the owner or an operator can claim an unowned legacy display, trust/untrust a player UUID, transfer ownership, change viewing/content-control policies, or relink the display. Transfers require confirmation and clear the old trusted list. Operators retain administrative access. Policy buttons preview locally; press Apply to submit the selected policy without exposing intermediate permissions. Policy edits return the latest server revision; stale edits are rejected and refreshed.

| Policy | Who can use the selected permission |
| --- | --- |
| PUBLIC | Everyone; anonymous automation may use explicitly public control |
| TRUSTED | Owner, trusted player UUIDs, and operators |
| OWNER_ONLY | Owner and operators |
| OPERATORS | Operators, plus the owner who retains management/control |

Viewing defaults to PUBLIC; content control defaults to OWNER_ONLY. Trust membership only applies when the corresponding policy is TRUSTED. Trusted content control allows selecting the caller's media and using upload/import, without granting access management or relinking. The caller must still be nearby and able to interact with the block. Aspect ratio stays local to the physical display. A manager can enable **Local image override** to change one linked display without changing the shared image; disabling it rejoins the network's current image.

Network ownership and library ownership are separate. Showing an image on a public screen permits viewers receiving that screen's chunk to fetch those displayed bytes. It does not list or grant selection/deletion rights to the owner's unused library. Merely possessing an image UUID does not authorize a download. Owners may download their own media; operators may inspect media; legacy ownerless library assets retain their existing public treatment. Authorization is checked before queuing and on the server thread before every outgoing chunk. Disconnect, dimension change, chunk untracking, removal/replacement of the display, content change, or access revocation blocks subsequent unauthorized chunks. Bytes already delivered to a client cannot be recalled.

The media index contains loaded tiles only and is maintained on load, content/link changes, and removal. A download examines references for the requested image and validates the exact loaded tile and vanilla chunk tracking; it neither scans world chunks nor loads chunks to obtain authorization. Child tiles can authorize viewing when their anchor is unloaded. These checks use actual chunk tracking, including for Sable displays; no coordinate-only distance approximation grants media access.

## Upgrade and recovery

Keep the world, image directory, old registry JSON files, and configuration. On first startup, legacy named images and ownership are imported into `screen_networks.json`. Existing owned networks retain their owner and image. Legacy records without owners remain publicly viewable but require an operator to claim before editing; this also applies to historical blank-ID displays. Newly placed player displays belong to their placer. Automation placement is unclaimed and does not inherit an operator/player identity.

The new versioned store is authoritative for selected network media, ownership, trusted players, policies, and revisions. A record also reserves STATIC mode, slide index and timeline offset fields for future playback work; this release plays static images only. Per-display identity, local override, aspect ratio and geometry remain in block-entity saved data. Linked displays synchronize the shared media across dimensions and reconcile loaded data from the authoritative record; a deliberately blank network is not repopulated by an old tile. Splits inherit access settings and current content but receive independent physical identities and stop following the original network once their loaded structure is resolved. Detaching a display preserves its physical identity so unloaded tiles reconcile with the detached state. Screens with different owners or access settings do not automatically merge.

Writes use a temporary file and atomic replacement where available, retaining `.bak`. A persistence error rolls the in-memory mutation back. A corrupt primary is recovered from a validated backup. If both new files are unreadable, startup fails closed instead of silently replacing permissions with legacy defaults. Preserve both files and restore a known-good world backup before retrying. Recovery from `.bak` restores that snapshot's state, including its permissions. Downgrading after changing access settings is unsupported; restore the corresponding pre-upgrade world backup instead.

## Integration boundary

Player packets derive actor identity and operator status from the connection. Content mutations, relinks and access edits use `ScreenAccessService` and the shared `ScreenNetworkService`; anonymous automation has no implicit owner or operator rights. Snapshots expose immutable trusted sets. Manager snapshots include trust membership; other nearby requests receive redacted snapshots. Internal structure/import/deletion reconciliation methods are not a supported public mod API. A stable Java API and lifecycle events remain issue #16.

## Verification

`testAllVersions` runs the shared migration/recovery/permission suite and version-specific native adapter tests. Coverage includes private UUID requests being rejected before file/worker scheduling, tracked public displays, chunk untracking/unload, stale tile instances, dimension/disconnect changes, policy revocation, distinct library/control/manage permissions, invalid/stale mutations, ownership transfer, local selection, foreign relinks, and detach behavior. Existing loader joins, production packaging, framebuffer and shader workflows remain required release checks. Those runtime workflows complement the mocked service tests; they are not a two-human multiplayer permission test.
