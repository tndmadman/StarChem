# StarChem v1.2.0

StarChem v1.2.0 is a major production, progression, multiplayer synchronization, and accessibility update built on the v1.1.0-alpha foundation.

## Highlights

- Added server-authoritative auto-production planning for ships, station packages, manufactured items, and research.
- Fixed the clean-game research bootstrap and related progression deadlocks.
- Made auto-production requests appear immediately in the real station queue.
- Synchronized completed research through authoritative multiplayer snapshots.
- Improved queued-ship presentation with the correct hull, defensive statistics, and weapon badges.
- Added optional narrated notices with persistent voice, volume, and speed settings.

## Auto-production and station queues

When a requested ship, station package, manufactured item, or research topic cannot be funded immediately, the authoritative server can create an auto-production plan.

The planner:

- recursively resolves manufactured prerequisites through the JSON recipe graph
- selects an owned compatible station in the same active star system
- prefers operational stations with shorter queues
- uses logistics shuttles when materials are stored in another owned hangar
- reports exact raw-material shortages
- pauses on missing research, raw materials, or specialized stations and resumes when the blocker is resolved

Accepted plans create a visible waiting job in the destination station queue immediately. The visible job ID and queue position are preserved while prerequisites are produced or transported. Cancelling the visible root also cleans up its planner state.

Stations may temporarily run a later funded job when the first visible job is waiting for resources. This does not permanently reorder the player's queue. Queue details distinguish between ordinary cancellation and cancellation with a reserved-resource refund.

## Research and progression fixes

The progression graph has been corrected so a clean non-developer game can reach its first Research Lab and continue through the research tree.

Changes include:

- Radiation Shielding is available before Advanced Industry.
- Combat Doctrine now requires Sensor Arrays instead of Circuit Fragments.
- Scout and Hauler are unlocked by Advanced Industry.
- Point-Defense Laser Assembly is unlocked by Advanced Industry.
- Validation now checks station reachability, transitive recipe research, salvage capability, effective ship technology tiers, starter packages, and the first Research Lab chain.

## Multiplayer synchronization and queue presentation

Completed research is now included in authoritative snapshots. Research state is deterministically serialized, strictly validated, and replaced from each accepted snapshot so clients do not lose completed topics when a research queue entry finishes.

Auto-production roots use the existing production-queue snapshot format, so waiting ship, station-package, crafting, and research requests remain visible to remote clients.

Queued ships now render with their actual ship card, silhouette, hull and shield values, and weapon badges. Non-ship jobs remain compact.

## Notices and narration

The authoritative world now produces player-targeted structured notices for plan creation, exact shortages, missing requirements, prerequisite jobs, and final queue readiness.

Remote notices are delivered through ordered TCP traffic and repeated identical notices are rate-limited.

Press **F8** in the graphical client to configure narration, including enable/disable, installed voice, volume, speech speed, and voice testing. Settings persist per operating-system user. Dedicated and headless servers do not run speech commands.

## Current scope

Auto-production currently considers owned stations and hangars in the same active star system. Cross-system production planning is not included in v1.2.0.

Narration quality and available voices depend on the operating system and installed speech backend.

## Compatibility

StarChem v1.2.0 clients and servers must use the same release package and matching packaged configuration files. Earlier builds, including v1.1.0-alpha and v1.1.0-alpha.1, are not multiplayer-compatible with v1.2.0.

## Validation

The merged implementation passed both repository CI workflows, including the full Gradle validator suite, release-identity checks, packaging checks, and the Linux headless-server process smoke test.

The release workflow performs a clean Java 17 build, runs `gradle clean check jar`, verifies runtime version metadata, smoke-tests the packaged JAR, validates the release layout and legal notices, and attaches the compiled ZIP to the GitHub release.

## Requirements

- Java 17 or newer.
- Extract the complete release ZIP before launching.
- Windows: start with `run-starchem.bat`.
- Linux client: run `./run-starchem.sh`.
- Linux dedicated server: run `./run-starchem-server.sh`.
- Direct launch remains available through `java -jar StarChem.jar`.
