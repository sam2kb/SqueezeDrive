# Architecture

SqueezeDrive adapts Squeeze Client's Lyrion client for a car-only AAOS app. This note shows who
owns what and how the pieces connect. Source layout:

- `com.sam2kb.squeezedrive.*` — SqueezeDrive's own code (media service, browse tree, parked UI)
- `de.maniac103.squeezeclient.*` — imported donor code (Lyrion protocol, models, Slimproto local
  player, media-session player), kept in the donor's packages so upstream fixes can be adopted as
  patches. See [UPSTREAM.md](UPSTREAM.md).

## The three layers

```
        car (AAOS media host: browse UI, transport, voice, steering wheel)
              │  MediaLibrarySession (Media3) + legacy MediaBrowser compat
              ▼
  PlaybackService (MediaLibraryService)  ── owns ──► SqueezeboxMediaPlayer (Media3 Player)
        │          browse: BrowseTree ↔ MediaIds                    │
        │                                                         │ player state
        ▼                                                         ▼
  ConnectionHelper (cometd/JSON-RPC over HTTP, port 9000) ◄── Lyrion server
        ▲                                                         │
        │ slimproto control (port 3483)                           │ audio stream (HTTP)
        │                                                         ▼
  LocalPlaybackService ── owns ──► SlimprotoSocket + LocalPlayer (ExoPlayer)
        │
        ▼
   car audio system ("This car" Lyrion player)
```

### 1. Lyrion connection / catalogue — `de.maniac103.squeezeclient.cometd`

`ConnectionHelper` holds the single cometd session per process: server status subscription,
player discovery, one-shot request/response, favourites add/delete/exists, browse actions
(`fetchItemsForAction`), queue/playlist operations, search. Connection states are a
`StateFlow<ConnectionState>` (Disconnected / Connecting / Connected / ConnectionFailure) with
bounded reconnect and idle disconnect. Credentials live in private `SharedPreferences`
(`ServerConfiguration`) and are attached by an OkHttp interceptor scoped to the configured server
host; they never appear in logs, error messages, or artwork URLs.

### 2. Playback / session — `com.sam2kb.squeezedrive.media` + donor `service/mediasession`

`PlaybackService` is a Media3 `MediaLibraryService`. It owns exactly one `MediaLibrarySession`,
whose `Player` is `SqueezeboxMediaPlayer` — a `SimpleBasePlayer` **proxy** for the active Lyrion
player. There is no second ExoPlayer for the UI: session state (metadata, queue, position,
playback state) is Lyrion's own player status, and every command the host issues (play/pause,
seek, next/previous, volume) is translated into the corresponding Lyrion request. The donor's
reviewed playback fixes live in `SqueezeboxMediaPlayer`/`PendingPlayerCommands` (state
coalescing, playlist-revision reconciliation, command acknowledgement) and are used unchanged.

The active player is **always this car**:
`PlayerIdentity.localPlayerId()` derives a stable pseudo-MAC from a per-install random device
identifier (no VIN, no hardware MAC), which is also the ID the Slimproto handshake registers with
the server. Both the session and the local player therefore address the same Lyrion player.

### 3. Local audio — `de.maniac103.squeezeclient.service.localplayer`

`LocalPlaybackService` (foreground service, media playback type) keeps `SlimprotoSocket`
connected to the server and plays the stream LMS pushes to this player with `LocalPlayer`
(Media3 ExoPlayer over a custom data source, incl. FLAC metadata handling and the donor's
seek/reconnect work). It is started when the app is used and when playback is requested; it is
not a boot receiver — nothing plays automatically at boot. Playback belongs to this service and
survives the parked settings activity being destroyed.

## Browse tree

`BrowseTree` maps Lyrion browsing onto `MediaItem`s through the media session:

- `sd:root` → **Favourites**, **Random tracks**, **Library**, **Queue**
- Favourites/Library children come from the server's own browse actions (home menu), so folders,
  playlists, radio entries and server-provided actions keep working; IDs are deterministic
  (`sd:j:<base64url of the server action>`), so they are stable across sessions.
- Queue rows (`sd:q:<index>`) mirror the server's playlist and support play/remove/clear.
- **Random tracks**: Lyrion's Random Mix (`randomplay tracks`, an ongoing mix) when the server
  provides it; otherwise a bounded, catalogue-backed one-off selection of ~25 tracks fetched
  from a random offset in the track listing. The node states which mode is active.

All browsing is asynchronous with loading/empty/error states; when disconnected the service asks
`ConnectionHelper` to connect and reports a browse error the host can retry.

## Parked UI

`SettingsActivity` (AppCompat + Preference, `ACTION_APPLICATION_PREFERENCES`) is the only
activity: server setup, connection status, player identity, and essential preferences. It is
deliberately not distraction-optimized and declares no launcher intent — the media host is the
driving interface, per AAOS guidance.

## Artwork

Server artwork URLs are resolved against the configured server and loaded by the session's
`CacheBitmapLoader` (OkHttp with the scoped auth interceptor) with a bounded in-memory cache;
there is no disk cache and no explicit request sizing in this pass. Cached by URL, so progress
updates do not re-download artwork. The URLs exposed in media metadata contain no credentials;
missing artwork falls back to the host's default icon.
