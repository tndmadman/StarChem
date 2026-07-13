# StarChem v1.3.0

StarChem v1.3.0 improves multiplayer connection handling, production automation, research progression, queue visibility, and client narration.

## Multiplayer joining and reconnecting

- Added an explicit synchronization phase between server acceptance and gameplay readiness.
- Clients no longer display **FLEET DESTROYED** while the authoritative fleet snapshot is still loading.
- Added a modal connection and reconnection display with staged progress, status details, elapsed time, and cancellation.
- Gameplay commands remain blocked until the initial authoritative world state has been validated and applied.
- Reconnecting clients preserve the current world behind the loading overlay until the resumed session is synchronized.
- Join failures and connection refusals return to the lobby without briefly exposing an empty game state.

## Server auto-production

- Added server-authoritative production planning for ships, station packages, manufactured items, and research.
- Production plans recursively resolve intermediate components through the configured recipe graph.
- Compatible owned stations are selected automatically within the active system.
- Existing logistics shuttles move prerequisite materials between owned hangars when necessary.
- Plans remain pending when blocked by missing raw resources, research, or specialized stations and resume when requirements are met.
- Player notices report specific resource shortages instead of a generic failure.
- Stations may temporarily run a later funded job while an earlier entry is waiting for resources without permanently reordering the visible queue.

## Research and production fixes

- Removed progression deadlocks around Radiation Shielding, Advanced Industry, Combat Doctrine, and the first Research Lab chain.
- Added completed research to authoritative multiplayer snapshots so client research state remains synchronized.
- Outpost ship and station-package requests now appear immediately as real waiting queue entries.
- Auto-production roots remain visible while gathering or manufacturing prerequisites.
- Cancelling a visible root also cleans up its linked production plan.
- Queued ships now display their actual ship card, silhouette, defenses, and weapon information rather than a generic row.

## Narration and interface

- Added persistent narration settings opened with **F8**.
- Supports narration enable/disable, installed voice selection, volume, speed, and test playback.
- New installations default narration speed to 1.5× while preserving existing preferences.
- Server-generated notices can be narrated on supported Windows, macOS, and Linux systems.
- Headless dedicated servers do not initialize audio or text-to-speech processes.
- The in-game tips strip now includes the Inventory and Narration shortcuts.

## Validation and packaging

- Updated session-recovery and snapshot-hardening validation for the authoritative synchronization lifecycle.
- Added regression coverage preventing clients from reporting ready before their initial assets exist.
- The release workflow runs the full Gradle validator suite, JAR build, Linux headless-server process smoke test, package-layout checks, and release identity verification.
- The release ZIP contains `StarChem.jar`, configuration files, Windows and Linux client launchers, the Linux dedicated-server launcher, README, license, and third-party notices.

## Compatibility

All multiplayer clients and servers must use the same v1.3.0 release package and matching configuration files. Older builds are rejected by the compatibility handshake.

## Requirements

- Java 17 or newer.
- Extract the complete release ZIP before launching.
- Windows: start with `run-starchem.bat`.
- Linux client: run `./run-starchem.sh`.
- Linux dedicated server: run `./run-starchem-server.sh`.
- Direct launch remains available through `java -jar StarChem.jar`.

## License

StarChem is proprietary software. Official unmodified compiled releases are licensed for personal, non-commercial use under the packaged `LICENSE`. Source code, configuration, assets, and other protected material may not be copied, modified, compiled, redistributed, sold, reused, or incorporated into another project without prior written permission.
