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

## AAOS architecture references

- [Add Android Automotive OS support to a media app](https://developer.android.com/training/cars/media/automotive-os): the standard AAOS media experience is rendered by the vehicle's media host.
- [Serve content with MediaLibraryService](https://developer.android.com/media/media3/session/serve-content): provide a browsable library through MediaLibrarySession and the browser compatibility action.

The earlier capability probe is a separate parked diagnostic app. Its activity restrictions, reports, signing configuration, and application ID are not the foundation for SqueezeDrive's media architecture.
