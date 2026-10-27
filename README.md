# SqueezeDrive

**Lyrion music for your car**

SqueezeDrive is a planned Android Automotive OS music app for browsing a Lyrion library and playing it through the car. Favourites, random tracks, album art, track details, and queue controls are the core of the first version.

**Status:** project brief ready; the app has not been implemented in this repository.

## Start here

Read the [MVP implementation job](docs/MVP_JOB.md). It defines the first build, the car interface, playback requirements, and handover deliverables.

The first pass is for scaffolding and implementation. Do not write or run tests yet; testing and refinement will follow in a second pass. Compiling and packaging the app are part of the first pass.

## Platform

- Installed directly on Android Automotive OS, with the Cadillac LYRIQ as the first target.
- Playback through the car's local player, backed by a Lyrion Music Server.
- Driving controls and browsing through the AAOS system media interface.
- Parked setup for server connection and preferences.

This project will reuse suitable code from [Squeeze Client](https://github.com/sam2kb/squeezeclient). See [upstream provenance](docs/UPSTREAM.md) and the [GPL v3 license](LICENSE).
