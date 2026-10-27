# Working on SqueezeDrive

Read README.md, docs/MVP_JOB.md, and docs/UPSTREAM.md before implementation.

## Current phase

Implement the first working MVP for later testing. The owner explicitly requested **no tests in this pass**.

- Do not add or run unit, instrumentation, UI, snapshot, or other test suites.
- Do not add test dependencies, coverage tooling, or test CI.
- Inspect existing upstream tests as reference if useful; do not import their suites during this phase.
- Compile and package the application. Report build results and unverified runtime behaviour separately.
- Do not claim device validation that did not happen.

## Product constraints

- Name: SqueezeDrive. Short description: Lyrion music for your car.
- Car-only AAOS application; package com.sam2kb.squeezedrive.
- Real local playback through the car is essential.
- Use the AAOS media host for the driving experience. Do not bypass driving restrictions.
- Preserve useful donor playback fixes and GPL notices. Record adopted revisions.
- Keep server credentials, signing keys, local configuration, personal media, and device reports out of Git.
- Do not publish to Google Play, create production signing keys, or reuse another app's keys unless separately requested.

## Continuity

Read .codex/HANDOFF.md if it exists, then inspect Git status, relevant diffs, and recent history. Keep a short local handoff with the goal, decisions, changed files, build results, limitations, and next steps. Keep it ignored and uncommitted unless the owner asks otherwise.
