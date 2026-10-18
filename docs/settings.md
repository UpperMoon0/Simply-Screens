# Settings ownership audit

Audited Config, both Fabric config handlers, the renderer and block-entity ticking.

| Setting | Owner and interface |
| --- | --- |
| Screen render distance | Personal; screen Settings > Your display settings, 8-512 blocks. Applies immediately to all screens for you. |
| Theme | Personal; existing header theme toggle. |
| Aspect ratio, linking and local image override | Screen/network settings; existing Settings controls and server authority. |
| View/control policies, ownership and trusted players | Network settings; existing access-management UI and server authority. |
| screenTickRate | Server structure-reconciliation interval; administrator configuration. |
| disableUpload, disableUrlDownload | Server upload/download policy; administrator configuration. |
| maxUploadSize, maxUrlDownloadSize | Server transfer limits; administrator configuration. |
| maxImagesPerPlayer, maxStoragePerPlayer, maxStorageTotal | Server storage quotas; administrator configuration. |
| debugRendering | Diagnostic draw logging, not a visual preference; remains mod configuration. |

Render distance is now saved with the existing personal UI preferences, not generated as a
mod-config option. An old viewDistance value is imported once without rewriting other server
policy values. Existing personal values win. Theme and unknown preference keys are preserved.
The renderer uses this local preference; changing it does not change visibility permissions,
other players' settings or the server's chunk view distance. The slider is available to read-only users.
