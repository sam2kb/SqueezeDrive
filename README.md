# SqueezeDrive

**Lyrion music for your car**

SqueezeDrive is an Android Automotive OS music app for browsing a Lyrion (Lyrion Music Server /
LMS) library and playing it through the car. Favourites, random tracks, album art, track details,
and queue controls are the core of the first version.

- Car-only AAOS application, package `com.sam2kb.squeezedrive` (debug: `com.sam2kb.squeezedrive.debug`)
- Version 0.1.0 (version code 1), baseline AAOS API 29+, first target API 34 (Cadillac LYRIQ)
- Playback happens **in the car**: SqueezeDrive registers a persistent local Lyrion player
  ("This car") and streams from the server through the car's speakers (Slimproto, FLAC included)
- Browsing and playback controls are rendered by the vehicle's standard AAOS media host
- Licensed GPL v3 — see [LICENSE](LICENSE). Donor provenance: [docs/UPSTREAM.md](docs/UPSTREAM.md)

## Requirements

| Tool | Version |
| --- | --- |
| JDK | 17+ (builds verified with Temurin 21) |
| Android SDK | platform `android-37`, build-tools 37.x (`compileSdk 37`, `targetSdk 36`, `minSdk 29`) |
| Gradle | 9.6.1 via the included wrapper (`./gradlew`) |
| Kotlin | 2.4.20 (via the Android Gradle Plugin 9.2.1 toolchain) |

`ANDROID_HOME` (or `local.properties`) must point at an SDK containing platform 37; the Gradle
wrapper downloads Gradle itself.

## Building

```sh
# Debug APK (applicationId com.sam2kb.squeezedrive.debug)
./gradlew assembleDebug

# Unsigned release AAB (signing stays external — no keys live in this repository)
./gradlew bundleRelease
```

Artifacts:

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release AAB (unsigned): `app/build/outputs/bundle/release/app-release.aab`

Release builds are minified and resource-shrunk but intentionally **unsigned**. When a Play
release is wanted later, sign the AAB externally (`jarsigner`/`bundletool` or Play App Signing)
and keep keystores out of Git.

## Installing on the car (ADB)

AAOS devices expose ADB over USB or Wi-Fi (head-unit developer mode):

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then open the car's **media source picker** (the vehicle's media host) and choose **SqueezeDrive**.
SqueezeDrive has no launcher icon by design: the media host is the driving interface.

## First-time setup (parked)

1. Park the car. In the media host's app list, open SqueezeDrive's **settings** (gear icon —
   the standard AAOS settings entry, available while parked).
2. Under **Lyrion server**, enter a name, the server address (`host` or `host:port`; the scheme is
   optional — bare addresses mean `http://`, use `https://host[:port]` if your server provides TLS;
   the default web port is `9000`), and optional username/password. Connection status shows live
   on the same screen, with a Reconnect action.
3. Under **This car (local player)**, check the player name (default **This car**). SqueezeDrive
   registers this name with Lyrion as a persistent player when it connects.
4. Back out to the media host: browse **Favourites**, **Random tracks**, **Library**, and
   **Queue**; tap anything to play through the car.

Playback continues when the settings screen is closed — it belongs to the media service, not the
activity. Steering-wheel and on-screen transport controls, volume, and navigation ducking go
through the standard media session. The Slimproto port is 3483; SqueezeDrive keeps the web/API
port (9000) and the streaming role distinct.

## What is verified, what is not

Build and packaging are verified (see [docs/STATUS.md](docs/STATUS.md) for the exact commands).
Runtime behaviour on a real vehicle — streaming quality, host rendering, driving restrictions —
has **not** been device-validated and is listed as unverified in the status document. Do not
treat a successful build as proof of in-car behaviour.

## Documentation

- [docs/MVP_JOB.md](docs/MVP_JOB.md) — the implementation job this pass answers to
- [docs/UPSTREAM.md](docs/UPSTREAM.md) — donor provenance, imported revisions, licensing
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — session/player ownership and data flow
- [docs/STATUS.md](docs/STATUS.md) — feature status, limitations, unverified behaviour
