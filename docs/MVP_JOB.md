# Job: scaffold and implement the SqueezeDrive AAOS MVP

## Goal

Build the first working version of **SqueezeDrive**, with **“Lyrion music for your car”** as its short description.

This is a car-only Android Automotive OS app. The first target is a Cadillac LYRIQ running Android 14, but the implementation should use standard AAOS APIs and adapt to other supported vehicle screens.

Deliver enough real functionality for the owner to install and assess the app in a second pass: connect to Lyrion, browse the library, play through the car, use favourites and random tracks, see artwork and track details, and manage the queue. A screen mockup or a remote control for a player at home does not complete this job.

**No tests in this pass.** Do not author or run unit, instrumentation, UI, snapshot, or other test suites. Do not add test dependencies, coverage tooling, or test CI. Compiling and packaging are required. Record runtime behaviour that remains unverified for the owner's second pass.

## Starting point and project identity

Use [sam2kb/squeezeclient](https://github.com/sam2kb/squeezeclient) as the preferred donor. Start from the revision recorded in [UPSTREAM.md](UPSTREAM.md), inspect its existing architecture, and reuse the Lyrion client, local player, stream handling, and useful playback fixes. Preserve GPL licensing, copyright headers, and dependency notices. Record exactly what was adopted.

Create a focused AAOS project here. Do not carry over the phone navigation drawer, Wear application, download workers, or unrelated product surfaces simply because they exist upstream. Avoid rewriting functioning protocol code during the interface port.

- App label: **SqueezeDrive**
- Short description: **Lyrion music for your car**
- Application ID: **com.sam2kb.squeezedrive**
- Debug application ID: **com.sam2kb.squeezedrive.debug**
- Initial version: **0.1.0**, version code **1**
- Baseline: AAOS API 29+, including the first target on API 34.
- Kotlin and Media3; retain a coherent donor-compatible Gradle/JDK/SDK setup. Document exact versions and commands. A toolchain change must solve a concrete build or platform requirement.
- Require automotive hardware in the manifest and declare an audio media application and the appropriate automotive media descriptor.

This is a separate product from the LYRIQ capability probe. Do not import its diagnostic screens, vehicle probes, application ID, reports, credentials, or signing material.

## Car interface and driving behaviour

Use the standard **AAOS media host** for browsing and playback while driving. Adapt the donor's session integration to a Media3 **MediaLibraryService / MediaLibrarySession**, including the legacy media-browser compatibility action needed by the host. Expose stable browsable and playable media IDs, metadata, queue state, and supported commands.

The host owns the driving layout. Supply a shallow, useful content hierarchy and artwork rather than promising a custom full-screen driving layout. Do not implement a phone UI in a WebView or label an ordinary activity distraction-optimized to evade restrictions.

Provide parked server setup and preferences through the supported settings/sign-in entry points. Any custom browse or design-preview activity beyond those entry points must be optional, parked-only, and excluded from the Play-targeted build until its distribution eligibility is established. It must share the same playback/session state.

Playback must belong to the service and continue when a parked activity is hidden or destroyed. Do not carry over the capability probe's stop-on-driving logic. Respect OEM restrictions, audio focus, and the system's media controls.

### Information architecture

Aim for these destinations, adapted to host limits rather than forcing six top-level tabs:

| Destination | Primary contents |
| --- | --- |
| Home | Favourites, Random tracks, Library, and a clear route to current playback |
| Favourites | Server favourites, folders, tracks, albums, playlists, and stream entries |
| Library | Artists, albums, tracks, genres, saved playlists, and search |
| Now Playing | Artwork, title, artist, album, transport, progress, and available actions |
| Queue | Current queue, selected item, and supported queue actions |
| Settings, parked | Server, connection status, player identity, and essential preferences |

Keep the path from opening the app to playing a favourite or random tracks short. Every list needs meaningful loading, empty, disconnected, and error states.

### Visual direction

For app-owned parked screens, use large readable text, generous spacing, strong contrast, and a quiet dark theme with one restrained accent. Let artwork carry most of the colour.

Use roughly 22–24sp body text, secondary text around 18sp or larger, and 64–76dp primary control targets as starting design targets. Respect font scaling, window insets, available width, and platform guidance. Avoid tiny status text, dense tables, cramped menus, and gesture-only actions.

On a wide parked screen, an optional design preview may pair artwork and controls with browsing or queue content; narrow screens should stack cleanly. Host-rendered screen typography and control placement remain under the vehicle's control.

## Required functionality

### 1. Connection and onboarding

Provide manual server host/address, port, and optional authentication. Default Lyrion web/API port is 9000; local-player Slimproto normally uses 3483. Keep those roles distinct. Support the donor's connection and authentication capabilities; handle HTTP LAN servers deliberately and HTTPS where supported.

Save connection preferences and credentials using appropriate private storage. Never put credentials in Git, logs, artwork URLs exposed to other apps, or error messages.

Show connecting, connected, reconnecting, authentication failure, unreachable server, and unsupported operation states. “Connected” must reflect a usable Lyrion session, not just an open TCP socket. Use bounded retries/backoff, cancellation, and a visible retry action. Server discovery is optional and must not hold up manual connection.

### 2. Playback through this car

Local playback is a core requirement. Register a persistent per-install local player with Lyrion and default to **This car**. Generate its identity without collecting a VIN or a hardware MAC address.

Reuse the donor's Slimproto/local streaming implementation and supported codec paths. Preserve existing seek, reconnect, and FLAC-related work unless inspection establishes a specific reason to change it. Report actual supported formats; do not claim bit-perfect or high-resolution output without evidence.

Use one authoritative playback path and session. Do not create a second unrelated ExoPlayer just to populate the host UI. If remote-player selection is retained, make the active target unmistakable and keep this car as the default.

Support media/steering-wheel commands, foreground media playback, navigation ducking, focus loss, calls, and appropriate route changes through platform mechanisms. Use the car's normal volume controls for local playback. Avoid silent queue replacement or repeated play commands during reconnection, and do not start music automatically at boot.

### 3. Favourites

Use **Lyrion's server-backed favourites**. Browse nested folders and distinguish playable entries from folders. Support favourite tracks, albums, playlists, and radio/URL streams when the server exposes them.

Allow Play, Play next, and Add to queue where the entry supports those operations. Provide an add/remove favourite action for the current item when a valid favourite target is available. Persist changes on the server and refresh visible state. Do not implement a local-only star that looks like a Lyrion favourite.

Unavailable or unsupported entries must explain the problem without leaving dead controls.

### 4. Random tracks

Make **Random tracks** easy to reach from Home and the host's browse tree.

This starts random music from the server library; queue shuffle is a separate feature. Prefer Lyrion's Random Mix capability when available and display whether the user has started an ongoing mix or a finite random selection.

If the server lacks that capability, implement a real, bounded catalogue-backed fallback only if the API supports it efficiently. A batch of about 25 playable tracks is sufficient for a finite fallback. Do not fetch the whole library or pretend a fixed demo queue is random playback. If neither method is available, show the reason and a useful recovery path.

### 5. Library, playlists, and search

Browse artists, albums, tracks, genres, and saved playlists. Use stable IDs and paginated or bounded loading for large libraries. Album and playlist details should show tracks and offer Play all, Shuffle, Play next, and Add to queue as supported.

Support text search from a parked input surface and media-host search callbacks where available. Handle server search failures and empty results explicitly. Do not introduce a keyboard workflow while driving.

Saved playlists must be browsable and playable; playlist editing and administration can wait.

### 6. Now Playing and track information

Supply accurate session metadata to the host and any app-owned screen:

- Cover art with a clear placeholder when missing.
- Track title, artist, album, playback state, elapsed time, and duration.
- Play/pause, previous/next, and seek when the source supports seeking.
- Separate shuffle and repeat modes: off, one, and all.
- Favourite action and queue access through supported host actions.
- Clear current-player and connection information.

Expose additional track details in a parked details surface where practical: year, genre, codec, bitrate, sample rate, bit depth, channels, and source type when supplied by the server. Omit missing values or mark them unknown. Do not invent metadata or show a stream seek bar as functional when seeking is unavailable.

Let the host expose only commands supported by the current item/player.

### 7. Artwork

Load real server artwork asynchronously with bounded caching and sensible request sizing. Resolve relative URLs and authentication safely. Make artwork accessible to the media host without leaking credentials.

Keep artwork attached to the correct track/album during rapid changes. Include loading, missing-art, and failed-request placeholders. Avoid full-size downloads on every progress update.

### 8. Queue

Show the server's current queue and current item. Support selecting an item, Play next, append, remove, and a deliberate clear-queue action where the protocol permits. Keep queue IDs and indices consistent with the server.

Reflect changes made by another Lyrion controller. Prevent duplicate commands during reconnects and reconcile server state rather than maintaining a competing local queue. Queue reordering can be parked-only and may be deferred if it would delay the playback slice; record that explicitly.

## Implementation boundaries

Keep clear boundaries for Lyrion connection/catalogue, playback/session, and UI state. Use the donor's useful structure rather than building a speculative framework. Keep network and artwork work off the main thread, cancel obsolete work, and expose observable state to the UI.

Request only permissions needed for playback and connectivity. Handle local-network access for the selected target SDK as required. Keep logs concise and redact credentials and personal server/library details.

Leave these out of this MVP: phone and Wear apps, Android Auto projection, downloads/offline playback, multiroom grouping/sync controls, server administration, plugin management, camera or vehicle-property access, Bluetooth reporting, VPN features, and automatic boot playback.

An optional, clearly labelled demo mode may help UI development, but real connection and local playback remain required. Do not substitute fake data for an unfinished backend.

## Work order

1. Inspect the donor and record reuse, licensing, module structure, and toolchain.
2. Establish server connection, the car's local player, and a MediaLibrarySession with real playback state.
3. Expose host browsing, favourites, random tracks, library, and search.
4. Complete metadata, artwork, queue operations, and readable parked setup.
5. Compile/package the build and document implementation status and remaining limits.

Prioritise the complete connect → browse → play → control flow. If a core feature cannot be completed, explain the blocker and leave the limitation visible. Do not describe an empty scaffold as a finished MVP.

## Handover deliverables

- A buildable Android project, Gradle wrapper, original or properly licensed app icon, and SqueezeDrive branding.
- A debug APK for installation and an unsigned release AAB for future Play setup, with exact build commands and artifact paths. Signing credentials must be external. Do not create or reuse production keys, upload a Play release, or accept terms in this job.
- README instructions for the required JDK/SDK, building, installing via ADB, entering a Lyrion server, and choosing This car. Explain how the owner reaches the media host and parked setup.
- A brief architecture note showing session/player ownership and the connection between Lyrion, the local player, and the host.
- Updated upstream provenance and required notices.
- A status document mapping every required feature to implemented, partial, or blocked, plus known limitations and unverified runtime behaviour. List successful build commands separately from device observations.
- Screenshots only if actually captured; label design previews and demo data.

The resulting project should be ready for the owner's second pass on functionality, usability, and in-car behaviour. Do not claim car compatibility, successful streaming, or Play approval based solely on compilation.

## References

- [Donor and license provenance](UPSTREAM.md)
- [AAOS media application architecture](https://developer.android.com/training/cars/media/automotive-os)
- [Media3 browsable media library](https://developer.android.com/media/media3/session/serve-content)
- [Earlier AAOS feasibility assessment](https://github.com/sam2kb/lyriq-aaos-capability-probe/blob/main/docs/SQUEEZECLIENT_AAOS_PORT.md)
