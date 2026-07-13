# StarChem v1.1.0-alpha.1

StarChem v1.1.0-alpha.1 is the first post-alpha patch prerelease. It focuses on removing progression deadlocks, making station production reliable and visible, synchronizing completed research in multiplayer, and adding optional narrated server notices.

> **Alpha prerelease:** Core game and multiplayer systems are functional, but development is still active. Expect balance changes, incomplete features, compatibility breaks, and bugs. Please report reproducible problems through GitHub Issues.

## Highlights

- Added server-authoritative auto-production planning for ships, station packages, manufactured items, and research.
- Fixed the clean-game research bootstrap and related progression deadlocks found in v1.1.0-alpha.
- Made auto-production requests appear immediately in the real station queue instead of remaining invisible server-side plans.
- Synchronized completed research through authoritative multiplayer snapshots.
- Improved queued-ship presentation with the correct hull, defensive statistics, and weapon badges.
- Added optional narrated notices with persistent voice, volume, and speed settings.

## Auto-production and station queues

When a requested ship, station package, manufactured item, or research topic cannot be funded immediately, the authoritative server can now create an auto-production plan.

The planner:

- recursively resolves manufactured prerequisites through the JSON recipe graph
- selects an owned compatible station in the same active star system
- prefers operational stations with shorter queues
- uses the existing logistics shuttle system when materials are stored in another owned hangar
- reports exact raw-material shortages
- pauses on missing research, raw materials, or specialized stations and resumes when the blocker is resolved

Accepted plans now create a real visible waiting job in the destination station queue immediately. The visible job ID and queue position are preserved while prerequisites are produced or transported. Cancelling the visible root also cleans up its planner state.

Stations may temporarily run a later funded job when the first visible job is waiting for resources. This does not permanently reorder the player's queue.

Queue details now distinguish between ordinary cancellation and cancellation with a reserved-resource refund.

## Research and progression fixes

The progression graph has been corrected so a clean non-developer game can reach its first Research Lab and continue through the research tree.

Changes include:

- Radiation Shielding is available before Advanced Industry.
- Combat Doctrine now requires Sensor Arrays instead of Circuit Fragments.
- Scout and Hauler are unlocked by Advanced Industry.
- Point-Defense Laser Assembly is unlocked by Advanced Industry.
- Validation now checks station reachability, transitive recipe research, salvage capability, effective ship technology tiers, starter packages, and the first Research Lab chain.

These checks prevent syntactically valid configuration from creating an unreachable progression path.

## Multiplayer synchronization and queue presentation

Completed research is now included in authoritative snapshots. Research state is deterministically serialized, strictly validated, and replaced from each accepted snapshot so clients do not lose completed topics when a research queue entry finishes.

Auto-production roots use the existing production-queue snapshot format, so waiting ship, station-package, crafting, and research requests remain visible to remote clients.

Queued ships now render with their actual ship card, silhouette, hull and shield values, and weapon badges. Non-ship jobs remain compact.

## Notices and narration

The authoritative world now produces player-targeted structured notices for important production states, including:

- plan creation
- exact raw-material shortages
- missing research or station requirements
- prerequisite jobs being queued
- the final requested job becoming ready

Remote notices are delivered through ordered TCP traffic and repeated identical notices are rate-limited.

Press **F8** in the graphical client to configure narration:

- enable or disable narration
- choose an installed voice
- set volume
- set speech speed
- test the selected voice

Settings persist per operating-system user. StarChem uses Windows System Speech, macOS `say`, or Linux `espeak-ng`/`espeak` when available. Dedicated and headless servers do not open an audio device or run speech commands.

## Compatibility

StarChem v1.1.0-alpha.1 clients and servers must use the same release package and matching packaged configuration files.

Because multiplayer compatibility includes the application version, build commit, rules version, protocol version, and release-critical configuration fingerprint, v1.1.0-alpha clients are not compatible with v1.1.0-alpha.1 servers.

## Current scope

Auto-production currently considers owned stations and hangars in the same active star system. Cross-system production planning is not included in this prerelease.

Narration quality and available voices depend on the operating system and locally installed speech backend.

## Validation

The merged implementation passed both repository CI workflows, including the full Gradle validator suite, release-identity checks, packaging checks, and the real Linux headless-server process smoke test.

The release workflow performs a clean Java 17 build, runs `gradle clean check jar`, verifies runtime version metadata, smoke-tests the packaged JAR, validates the release layout and legal notices, and attaches the compiled ZIP to the GitHub prerelease.

## Requirements

- Java 17 or newer.
- Extract the complete release ZIP before launching.
- Windows: start with `run-starchem.bat`.
- Linux client: run `./run-starchem.sh`.
- Linux dedicated server: run `./run-starchem-server.sh`.
- Direct launch remains available through `java -jar StarChem.jar`.
