# StarChem v1.3.1

StarChem v1.3.1 packages the current `main` branch changes made after v1.3.0. This release focuses on research reliability, in-game navigation and reference tools, multiplayer audio delivery, and a small industrial ship positioning fix.

## Highlights

- Fixed Research Lab fuel use and automatic resupply.
- Added a tactical minimap with click-to-pan and contact pings.
- Added a searchable in-game codex generated from the loaded game rules.
- Upgraded the resource and system catalog with vector icons and graphical orbit previews.
- Fixed authoritative gameplay audio delivery for hosted and remote multiplayer clients.
- Kept the Salvager's idle orbit inside station unload range.

## Research Lab fuel and resupply

Research Labs now consume Fuel only while they have active, funded production or research work. Idle Labs and jobs waiting for missing resources no longer drain Fuel continuously.

When an active Lab falls below its fuel threshold, the authoritative simulation now checks stored Fuel, inbound shuttle cargo, and already queued Fuel output. If more Fuel is required, an eligible Manufacturing Plant automatically receives a Fuel production request through the normal production planner. Fuel shuttles continue moving the completed Fuel to the Lab.

## Tactical minimap

A responsive tactical minimap now appears in the lower-right game HUD. It displays resources, wormholes, friendly ships and stations, enemy and NPC contacts, and the current camera rectangle.

- Click inside the minimap to recenter the camera.
- Newly detected hostiles and wormholes create temporary pings.
- Lost friendly ships or stations create loss pings.
- Build, hangar, AI, and developer panels retain input priority when overlapping the minimap.
- Mouse-wheel input over the minimap no longer changes the world zoom unexpectedly.

## Searchable codex

The new codex is available from the lobby and with `F1` during a game. It is generated from the currently loaded rules and includes:

- ships and stations
- resources and manufactured materials
- research prerequisites and unlocks
- manufacturing recipes
- NPC factions
- controls

Entries can be filtered by category and searched by names, IDs, roles, stats, costs, requirements, descriptions, and unlock text. The codex is read-only and works in solo, hosted, and joined games.

## Graphical resource and system catalog

The `I` catalog now includes graphical material and system presentation instead of relying only on text.

- Added vector-drawn icons for raw resources, gases, electronics, power systems, chemicals, weapons, salvage, alloys, composites, industrial components, and capital components.
- Added a large selected-resource preview with family, rarity, and source badges.
- Added ship and station silhouette cards showing which build costs consume the selected material.
- Added graphical system icons.
- Added scaled orbit diagrams showing stars, planets, resource belts, orbital radii, and configured spread values.
- Preserved detailed text for exact counts, modifiers, rates, and celestial data.
- Preserved search, tab navigation, and keyboard controls.

## Multiplayer audio delivery

Gameplay audio events are now generated and routed from the authoritative simulation to the correct connected client.

This covers combat fire and impacts, destruction, harvesting and resource depletion, item pickup, tractor activity, production completion, station placement, wormhole entry and exit, and incoming alarms. Audio is scoped to the exact rendered world, preventing background server systems from playing through the local audio device. Early audio packets are buffered until the client listener is ready.

## Salvager positioning

The Salvager idle orbit radius was reduced so it remains inside the smallest station unload range. Collected salvage can now transfer during normal station orbit without manual repositioning.

## Validation

The included changes passed their full Gradle builds, repository validator suites, packaging checks, release-identity checks, and Linux headless-server smoke tests before merge. The v1.3.1 release workflow rebuilds and verifies the complete current source before publishing the release ZIP.

## Compatibility

Clients and servers must use the same StarChem version and packaged configuration. Use the v1.3.1 package for all participants in a multiplayer session.

## Requirements

- Java 17 or newer.
- Extract the complete release ZIP before launching.
- Windows: run `run-starchem.bat`.
- Linux client: run `./run-starchem.sh`.
- Linux dedicated server: run `./run-starchem-server.sh`.
