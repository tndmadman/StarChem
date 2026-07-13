# StarChem Wiki

Welcome to the documentation for **StarChem v1.1.0-alpha**.

StarChem is a Java 2D top-down multiplayer real-time strategy prototype built around mining, manufacturing, research, fleet construction, system exploration, territory control, and persistent multi-system simulation.

> **Alpha release:** Core solo and multiplayer systems are functional, but active development continues. Expect balance changes, incomplete features, compatibility breaks, and bugs.

## Release identity

| Item | Value |
|---|---|
| Release | `v1.1.0-alpha` |
| Tagged commit | `81075c4618bed69dca7a0e53f6d8ca4628683384` |
| Java requirement | Java 17 or newer |
| Rules version | 14 |
| NPC AI version | 5 |
| Multiplayer transport | Framed TCP |
| Default game port | TCP `50000` |
| Static system templates | 14 |
| Raw mineable materials | 24 |
| Manufactured intermediate materials | 56 |
| JSON-driven recipes | 60 |

This wiki documents the tagged release, not later changes on `main`. Post-release work such as server-side auto-production planning and text-to-speech narration is intentionally excluded.

## Start here

- [[Getting Started]] — download, install, launch, and start a first session.
- [[Controls and Interface]] — selection, camera, fleet orders, overlays, map, and resource catalog.
- [[First Game Progression]] — a practical route from the starting economy to advanced fleets.
- [[Multiplayer and Dedicated Server]] — Solo, Host, Join, headless Linux hosting, ports, and compatibility.

## Systems and progression

- [[Galaxy and Territory]] — all 14 system templates, topology, wormholes, capture rules, and control bonuses.
- [[Resources and Manufacturing]] — raw materials, salvage, all recipe categories, dependency chains, and recommended production order.
- [[Research Ships and Stations]] — research tree, every player hull, stations, costs, roles, and progression gates.
- [[Weapons and Combat]] — weapon statistics, ship loadouts, formations, targeting, and fleet commands.
- [[NPC Factions and Simulation]] — Raiders, Free Miners, Corsairs, background simulation, and NPC industry.

## Administration and technical reference

- [[Networking and Security]] — framing, limits, compatibility fingerprints, reconnects, snapshots, and trust boundaries.
- [[Configuration Troubleshooting and Development]] — JSON structure, launch options, developer access, common failures, and validation.
- [[v1.1.0-alpha Release Notes]] — release highlights, compatibility, requirements, and known alpha expectations.

## Core gameplay loop

1. Begin with a **Prospector** and **Outpost** in a protected home system.
2. Mine metals, minerals, ice, and gases.
3. Build a **Deployer** and establish a Manufacturing Plant, Research Lab, and Shipyard.
4. Convert raw resources into alloys, chemicals, electronics, industrial modules, power systems, weapons, and capital components.
5. Complete the research chain from Advanced Industry through Supercapital Architecture.
6. Explore the galaxy, exploit specialized systems, and contest valuable territory.
7. Defend against NPC factions and opposing players.
8. Progress from specialist industry ships and frigates to capitals, supercapitals, and the Monolith.

## Multiplayer compatibility warning

StarChem v0.1.5 clients cannot connect to v1.1.0-alpha servers. Every client and server must use the same release build and matching packaged configuration files.