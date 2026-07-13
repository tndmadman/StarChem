# StarChem Wiki

Welcome to the documentation for **StarChem v1.1.0-alpha**.

StarChem is a Java 2D top-down multiplayer real-time strategy prototype built around mining, manufacturing, research, fleet construction, system exploration, territory control, and persistent multi-system simulation.

> **Alpha release:** Core solo and multiplayer systems are functional, but active development continues. Expect balance changes, incomplete features, compatibility breaks, and bugs.

> **Known progression blocker:** The tagged release gives the Research Lab a Radiation Shielding cost, while Radiation Shielding requires Advanced Industry and Advanced Industry requires the Research Lab. A clean non-developer game cannot normally enter the research tree. See [[First Game Progression]] and [[v1.1.0-alpha Release Notes]].

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
- [[First Game Progression]] — achievable clean-release progression, the confirmed research blocker, and the intended later path.
- [[Multiplayer and Dedicated Server]] — Solo, Host, Join, headless Linux hosting, ports, and compatibility.

## Systems and progression

- [[Galaxy and Territory]] — all 14 system templates, topology, wormholes, capture rules, and control bonuses.
- [[Resources and Manufacturing]] — raw materials, salvage, all recipe categories, dependency chains, and recommended production order.
- [[Research Ships and Stations]] — research tree, every player hull, stations, costs, roles, progression gates, and the Lab bootstrap issue.
- [[Weapons and Combat]] — weapon statistics, ship loadouts, formations, targeting, and fleet commands.
- [[NPC Factions and Simulation]] — Raiders, Free Miners, Corsairs, background simulation, and NPC industry.

## Administration and technical reference

- [[Networking and Security]] — framing, limits, compatibility fingerprints, reconnects, snapshots, and trust boundaries.
- [[Configuration Troubleshooting and Development]] — JSON structure, launch options, developer access, common failures, and validation.
- [[v1.1.0-alpha Release Notes]] — release highlights, compatibility, requirements, known issues, and release boundaries.

## Achievable clean-release loop

1. Begin with a **Prospector** and **Outpost** in a protected home system.
2. Mine metals, minerals, ice, and gases.
3. Build more Prospectors and a **Deployer**.
4. Establish a **Manufacturing Plant** from raw resources.
5. Produce the immediately unlocked alloys, chemicals, electronics, industrial components, and Fuel.
6. Build and use infrastructure whose components do not depend on Advanced Industry.
7. Explore the galaxy, exploit specialized systems, contest territory, and respond to NPC factions.
8. Reach the documented Research Lab bootstrap blocker in normal non-developer progression.

## Intended later progression

After the release blocker is fixed or deliberately bypassed in a test environment, the configured path continues through Advanced Industry, Combat Doctrine, Battlefleet Engineering, Supercapital Architecture, specialist industry ships, combat fleets, capitals, supercapitals, and the Monolith.

## Multiplayer compatibility warning

StarChem v0.1.5 clients cannot connect to v1.1.0-alpha servers. Every client and server must use the same release build and matching packaged configuration files.