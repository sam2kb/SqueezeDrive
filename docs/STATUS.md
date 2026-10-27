# Implementation status — first MVP pass

Scope: [docs/MVP_JOB.md](MVP_JOB.md). Tests were explicitly **not** written or run in this pass
(owner's instruction); no test dependencies or test CI exist in the project.

## Build commands and results

Run inside the build container (JDK 21 + Android SDK 36/37) with the checkout mounted:

```sh
./gradlew assembleDebug     # debug APK
./gradlew bundleRelease     # unsigned release AAB
```

Both commands completed successfully in this pass (`BUILD SUCCESSFUL`, incl. R8 minification,
resource shrinking and `lintVitalRelease`), produced with Gradle 9.6.1 (wrapper), Android Gradle
Plugin 9.2.1, Kotlin 2.4.20, compileSdk 37, JDK 21. Compiler notes are cosmetic only: one redundant
`else` in a sealed `when` and one deprecated Java `SHOW_IMPLICIT` constant in the keyboard
handling of the parked search dialog (left as-is; behaviour is unaffected on API 29+).
Build environment used here: the `lyriq-android-build:local` container (JDK 21 + Android SDK
36/37) with the checkout mounted — the same commands work on any JDK 17+ / SDK 37 host.

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (applicationId `com.sam2kb.squeezedrive.debug`)
- Release AAB: `app/build/outputs/bundle/release/app-release.aab` — **unsigned** (verified: not a
  signed jar). No signing config, keystore, or Play upload exists in this repository; production
  signing is deliberately left to a later, explicitly authorized step.

Successful build commands are listed above and are **not** evidence of runtime or in-car
behaviour. Device validation has not been performed (no device in this environment).

## Feature status

| Required feature | Status | Notes |
| --- | --- | --- |
| 1. Connection and onboarding | Implemented | Manual host or host:port + optional basic-auth credentials in parked settings (scheme optional: bare addresses are HTTP, `https://` accepted where the server provides TLS); private storage; connection states (not connected / connecting / connected / failed) with live status and Reconnect action; bounded exponential backoff on retries in the media service (5–30s, giving up after 15 minutes); "connected" reflects a usable Lyrion session (server status received), not a bare socket. A malformed saved address reports a connection error instead of crashing. Server discovery intentionally not implemented (job lists it optional). |
| 2. Playback through this car | Implemented (unverified on device) | Persistent per-install local player ("This car"), identity = pseudo-MAC from a random UUID (no VIN/hardware MAC). Slimproto streaming and FLAC handling ported unchanged from the donor, incl. its seek/reconnect fixes. Single authoritative session: the media session proxies the Lyrion player; audio plays via the local Slimproto player. Transport, seek, volume through platform media session; no auto-play at boot; no stop-on-driving logic. |
| 3. Favourites | Implemented | Server-backed favourites via Lyrion browse actions (nested folders, folders vs playable entries, radio/URL streams as exposed). Play / Add-to-queue / Play-next from browse items (server-supplied actions). Add/remove favourite for the current item through the host's favourite button (custom-layout command; `sd.fav.toggle` with a URL argument for other controllers). No local-only star. Unsupported entries explain themselves via non-interactive info items or error results. |
| 4. Random tracks | Implemented (two modes) | "Random mix" starts Lyrion's ongoing Random Mix (`randomplay tracks`); "About 25 random tracks" is a finite one-off selection from a bounded catalogue fetch at a pseudorandom offset (never the whole library), shuffled, loaded via the server's own per-item actions. The Random node states which mode is active. If the server lacks Random Mix, the finite path still works; failures surface as session/browse errors with the informational item explaining availability. |
| 5. Library, playlists, search | Implemented | Artists/albums/tracks/genres/playlists browse via the server's own menu (stable IDs, paged fetches capped at 200/list). Album/playlist details show tracks; Play/queue actions per item and per folder via server actions. Search via the media host's search callbacks (library search, server-side). No keyboard workflow while driving (host-owned). Playlist editing/administration deferred (per job). |
| 6. Now Playing and track info | Partial | Host receives title/artist/album/artwork/state/position/duration; play/pause/prev/next/seek; queue access; favourite action (host button). Shuffle and repeat are exposed as the host's standard controls with truthful mode state (Lyrion `playlist shuffle/repeat`; repeat off/one/all, shuffle off/songs). Parked track-details surface (year/genre/codec/bitrate/sample rate) not implemented in this pass — recorded as a limitation. |
| 7. Artwork | Implemented (unverified on device) | Server artwork resolved against the configured server, loaded by the session bitmap loader (OkHttp, scoped auth interceptor, caching) with missing-art placeholders at browse level; artwork URLs carry no credentials. Rapid-change correctness follows the donor's revision-checked playlist logic. |
| 8. Queue | Implemented (reorder deferred) | Queue browse node mirrors the server playlist (IDs/indices consistent with the server); select item, append, play-next, remove, deliberate clear-queue (`sd.queue.clear`). Queue rows are positional snapshots: our own mutations (and plays) invalidate the browse list so hosts refetch; changes made by another Lyrion controller appear on the next refetch and immediately in playback state (which comes from server status subscriptions; no competing local queue). Queue reordering is deferred (job allows parking it); no duplicate commands during reconnects (donor's pending-command acknowledgement). |
| Parked settings | Implemented | Server setup, live connection status + reconnect, player identity ("This car" name), essential playback preferences. Reached through the AAOS settings entry (`ACTION_APPLICATION_PREFERENCES`), parked-only, not distraction-optimized. |
| Car interface | Implemented (unverified on device) | Media host owns the driving UI; MediaLibraryService + MediaLibrarySession with legacy `MediaBrowserService` compatibility action; shallow browse tree (Favourites / Random tracks / Library / Queue). No WebView, no custom distraction-optimized activity. |

## Known limitations (this pass)

- Shuffle and repeat modes are not exposed as distinct session commands.
- No parked track-details screen (year/genre/codec/bitrate/sample rate/bit depth/channels).
- Queue reordering deferred (parked-only feature per the job; queue play/remove/clear work).
- Server discovery not implemented (optional per the job); manual entry only.
- `sd.item.playNext` is a custom session command because Media3 has no insert callback; hosts
  that do not surface custom commands can still use add-to-queue and the queue view.
- Local playback runs as a foreground media service with its own small notification alongside
  the media session notification. Whether that satisfies AAOS Play distribution policy is a
  question for the (explicitly out-of-scope) Play pass.
- No offline/download support, no multiroom/sync controls, no server administration (all out of
  scope per the job).

## Unverified runtime behaviour (needs the owner's device pass)

Nothing below has been observed on real hardware; the list exists so the second pass knows what
to look at:

- Streaming to the car's audio system (all codecs, FLAC gapless/seek behaviour) end to end.
- The vehicle media host's rendering of our browse tree, artwork, and metadata (LYRIQ host).
- Steering-wheel controls, navigation ducking, call interruption, focus loss, route changes.
- Driving restrictions of the settings entry and error dialogs on the actual head unit.
- Reconnect behaviour on real network transitions (Wi-Fi/cellular handover in the car).
- Whether `randomplay` is present on the owner's server and how the mix behaves over time.
- Notification behaviour of the local playback service on the head unit.

## Notes on adopted donor code

See [UPSTREAM.md](UPSTREAM.md) for the exact revision, file boundaries, and adaptations.
