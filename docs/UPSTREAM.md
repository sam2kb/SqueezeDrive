# Upstream provenance

SqueezeDrive is intended to adapt the existing Squeeze Client code for a car-only AAOS app.

| Source | Role |
| --- | --- |
| [sam2kb/squeezeclient](https://github.com/sam2kb/squeezeclient) | Preferred donor fork, including its existing playback work |
| [maniac103/squeezeclient](https://github.com/maniac103/squeezeclient) | Original upstream project |
| [AAOS port assessment](https://github.com/sam2kb/lyriq-aaos-capability-probe/blob/main/docs/SQUEEZECLIENT_AAOS_PORT.md) | Earlier feasibility notes, not an implementation or proof of in-car streaming |

## Starting revision

The donor's main branch was inspected on 2026-10-26 at commit [4bcb826a3d1b3bba8e51a88cd196183e388d8737](https://github.com/sam2kb/squeezeclient/commit/4bcb826a3d1b3bba8e51a88cd196183e388d8737).

Useful starting points at that revision:

- [MediaService.kt](https://github.com/sam2kb/squeezeclient/blob/4bcb826a3d1b3bba8e51a88cd196183e388d8737/app/src/main/java/de/maniac103/squeezeclient/service/mediasession/MediaService.kt): existing MediaSessionService integration.
- [LocalPlaybackService.kt](https://github.com/sam2kb/squeezeclient/blob/4bcb826a3d1b3bba8e51a88cd196183e388d8737/app/src/main/java/de/maniac103/squeezeclient/service/localplayer/LocalPlaybackService.kt): local Slimproto playback.
- [Favourites requests](https://github.com/sam2kb/squeezeclient/tree/4bcb826a3d1b3bba8e51a88cd196183e388d8737/app/src/main/java/de/maniac103/squeezeclient/cometd/request): inspect existing server-backed add, delete, and existence support before introducing new logic.
- [App build configuration](https://github.com/sam2kb/squeezeclient/blob/4bcb826a3d1b3bba8e51a88cd196183e388d8737/app/build.gradle.kts): currently compile SDK 37 and target SDK 36.

## Reuse and licensing

The donor contains the GNU GPL version 3 license text. This repository includes that text as LICENSE. Retain applicable copyright headers, attribution, and third-party notices when importing code. Record the exact imported revision, file or module boundaries, dependency licenses, and any material playback changes here. Do not replace the donor's license with a permissive license.

No donor application source has been imported in the initial planning commit.

## Imported code (first MVP pass)

Imported from [sam2kb/squeezeclient](https://github.com/sam2kb/squeezeclient) at revision
[`4bcb826a3d1b3bba8e51a88cd196183e388d8737`](https://github.com/sam2kb/squeezeclient/commit/4bcb826a3d1b3bba8e51a88cd196183e388d8737)
("Bring main up to the reviewed playback implementation", 2026-10-23) — the revision recorded
above. Note: a local checkout of the donor on the build machine was found on a diverged, older
history (merge commits `fix/media-session-*`); the import deliberately came from the recorded
revision's tree, not that checkout.

Imported file boundaries (original package names and GPL headers retained so future donor fixes
can be adopted as patches; all under `app/src/main/java/de/maniac103/squeezeclient/`):

| Donor area | Scope |
| --- | --- |
| `cometd/` | Full Lyrion cometd client: connection handling, request/response layer, serializers |
| `model/` | Full model layer (players, playlist, browse items, Jive actions, server configuration) |
| `extfuncs/` | `ByteBufferExtensions`, `BundleExtensions`, `CoilExtensions`, `ContextExtensions`, `FlowExtensions`, `NotificationExtensions`, `PreferenceExtensions`, `SlimProtocol` (not imported: `FragmentExtensions`, `ViewExtensions`, `DrawableExtensions`, phone-UI helpers) |
| `service/localplayer/` | Full Slimproto local player: `SlimprotoSocket`, `LocalPlayer`, `LocalPlaybackService`, `LocalPlayerMediaExtractor`, `LocalPlayerAudioProcessor`, `LocalPlayerAudioOutputProvider`, `FlacMetadataCachingDataReader`, `LocalPlayerLoadErrorHandlingPolicy`, `LocalPlayerPosition`, `PositionChangeRequests`, `StreamPlaybackPosition`, `LocalPlayerStartupWorker` |
| `service/mediasession/` | `SqueezeboxMediaPlayer`, `PendingPlayerCommands` — the reviewed media-session player with its state-stability, lifetime, seek and reconnect handling |
| `service/NotificationIds.kt` | Notification channel definitions |

Not imported (out of MVP scope, per the job): the donor's `MediaService` (replaced by SqueezeDrive's
`MediaLibraryService` in `com.sam2kb.squeezedrive.media.PlaybackService`, which reuses
`SqueezeboxMediaPlayer` unchanged), phone UI (`ui/`), Wear modules, `DownloadWorker`, `BootReceiver`,
`CacheFileProvider`, slideshow, and download request/response classes (retained in the protocol
layer for parsing parity).

Material adaptations to imported files (recorded as required):

- `import de.maniac103.squeezeclient.R` → `import com.sam2kb.squeezedrive.R` (app namespace).
- `SqueezeClientApplication` references → `com.sam2kb.squeezedrive.SqueezeDriveApplication`
  (constructor shape kept: `json`, `httpClient`, `connectionHelper`).
- `LocalPlaybackService`: media-session trigger now starts SqueezeDrive's `PlaybackService`
  instead of the donor's `MediaService`; notification content intent opens the parked
  `SettingsActivity`; default local-player name is `This car` instead of
  `SqueezeClient - <model>`. The Slimproto streaming, status reporting and playback fix logic
  in that file are otherwise unchanged.
- `PreferenceExtensions.localPlayerEnabled` defaults to `true` (SqueezeDrive is car-only and
  defaults to this car's player).

No logic changes were made to the cometd protocol layer, the Slimproto socket, or
`SqueezeboxMediaPlayer`/`PendingPlayerCommands`. Donor dependencies (Kotlin, coroutines,
kotlinx-serialization, OkHttp, Media3, Coil, AndroidX) carry their existing open-source licenses
(Apache 2.0 / MIT); no new third-party dependency was added beyond the donor's set.

The donor's Gradle setup (Gradle 9.6.1 wrapper, AGP 9.2.1, Kotlin 2.4.20, compile SDK 37,
target SDK 36, JVM target 11, core library desugaring) is retained; SqueezeDrive sets
`minSdk = 29` (AAOS baseline) and application ID `com.sam2kb.squeezedrive`.

## AAOS architecture references

- [Add Android Automotive OS support to a media app](https://developer.android.com/training/cars/media/automotive-os): the standard AAOS media experience is rendered by the vehicle's media host.
- [Serve content with MediaLibraryService](https://developer.android.com/media/media3/session/serve-content): provide a browsable library through MediaLibrarySession and the browser compatibility action.

The earlier capability probe is a separate parked diagnostic app. Its activity restrictions, reports, signing configuration, and application ID are not the foundation for SqueezeDrive's media architecture.
