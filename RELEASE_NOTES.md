# StarChem v1.8.0

StarChem v1.8.0 is a major gameplay, multiplayer, fleet-control, industry, intelligence, persistence, and server-operations release. It advances the published v1.7.0 baseline from multiplayer protocol 8, rules version 14, and save format 2 to **protocol 17, rules version 27, and save format 6**.

## Fleet Control And Combat

- Added persistent client-local control groups with assign/add/remove, fast recall, split-system reconciliation, remembered formations, and double-tap camera focus.
- Added bounded server-authoritative queued waypoints and compound order chains for movement, attacks, harvesting, patrol, guard, escort, hold, and wormhole transit.
- Added persistent combat stances: Passive, Defensive, Aggressive, and Hold Fire.
- Added configurable target-priority policies including nearest threat, screening, combat-first, logistics-first, and structure priorities.
- Added radar-directed combat response that respects ownership, diplomacy, stance, target visibility, explicit player orders, response limits, and classified-contact information boundaries.
- Added player-authored private and shared ship fits, configured fitting rules, construction with exact authoritative costs, and Outpost/Shipyard refitting.
- Added fitted utility modules including afterburners, micro jump drives, and jump scramblers with authoritative multiplayer correction.

## Fog Of War, Radar, And Intelligence

- Added server-authoritative fog of war with explored space, current visibility, staged contact identification, last-known intelligence, uncertainty projection, and owner-scoped snapshot filtering.
- Replaced the old Scout reconnaissance role with deployable Radar Picket, Radar Array, and Radar Nexus stations.
- Added resource surveying, miner dispatch, adaptive radar modes, radar response fleets, jamming, counter-jamming, strategic decoys, and allied intelligence sharing.
- Added focused wormhole search and persistent remembered wormhole rendering on tactical maps.
- Persisted tactical exploration, discovered systems, and known wormholes across reconnects and saved-server restarts while retaining server authority over system-view access.
- Added fog/render and radar-resource caching work to keep the expanded intelligence system bounded on large galaxies.

## Diplomacy, Communications, And Observation

- Added free-for-all, fixed-team, co-op, and locked-alliance diplomacy modes with configurable friendly fire, shared vision, and shared victory.
- Added live in-game diplomacy with authoritative alliance offers, acceptance/decline/cancel flows, neutral relations, and hostility changes.
- Added global, system, team, and direct chat with bounded history, local blocking, server comms policy, and independent chat/ping rate limits.
- Added tactical map pings with server-side system, diplomacy, visibility, coordinate, and rate validation.
- Added dedicated-server observer sessions with separate slot limits, expiring invitations, PUBLIC / PLAYER_FOLLOW / FULL visibility policies, reconnect persistence, and hard rejection of gameplay/developer mutations from observer connections.
- Added LAN server discovery and a recent-server browser without bypassing the existing password, TLS-pinning, or compatibility paths.

## Industry, Logistics, And Production

- Added persistent physical inter-system logistics routes using real Hauler/Freighter cargo, authoritative wormhole paths, optional escorts, route priorities, stock targets, source reserves, save/restart recovery, and client resynchronization.
- Added cross-system production resource sourcing. Manufacturing can pull from owned stations in other systems using real logistics shuttles instead of virtual inventory transfer.
- Same-system production sourcing now exhausts the nearest viable station first and spills to farther stations only when needed.
- Added standing production policies for maintained stock, maintained fleets, and repeat production.
- Added station/network reserve floors, reusable production templates, persistent policy/job provenance, and explicit orphan recovery when a production station is lost.
- Station packages can wait behind a queued Deployer and claim it when production reaches the package job.
- Shipyards can now craft deployable station packages under the same research, resource, queue, and Deployer rules as other valid production stations.

## Galaxy, Events, And Strategic Management

- Added data-driven deterministic galaxy events with persistent lifecycle state, resource anomalies, salvage, distress encounters, pirate activity, temporary modifiers, unstable wormholes, staged objectives, event chains, hidden pockets, boss encounters, rewards, competition, and FOW-aware projection.
- Added configurable event enablement, frequency, categories, admin/developer controls, history, cleanup, and background-scheduler integration.
- Added a strategic empire overview for systems, fleets, stations, production, research, and alerts with owner-scoped bounded aggregation and navigation.
- Added skirmish presets and NPC difficulty settings with server-authoritative persistence and synchronization.
- Added ten data-driven victory-condition presets with authoritative progress, save state, networking, and Sandbox handling.

## User Interface And Quality Of Life

- Added guided Core and Advanced tutorials with replay, pause, skip, restart, and target guidance.
- Added the full in-game Settings menu for controls, audio, and display, plus the redesigned ESC game menu.
- Reworked production and station controls around native scrollable Swing menus, compact previews, immediate game-styled tooltips, and consistent scrollbar presentation.
- Fixed long station/policy descriptions expanding popup width and forcing horizontal scrollbars.
- Added role-specific station controls for production, radar, jamming, decoys, logistics, and production policies.
- Added lobby controls for skirmish setup, diplomacy, victory settings, LAN/recent servers, and related multiplayer configuration.

## Persistence And Upgrade Safety

- Current v1.8.0 dedicated-server saves use save format **6**.
- The release gate generates a real save and companion-state directory using the exact published v1.7.0 source commit, then loads it with current code, verifies retained players/assets/research/inventory/production/loadout migration, exercises remembered-session resume and password reclaim, preserves TLS/admin/observation/activity state, resaves it at the current format, restarts, and validates it again.
- Current and release CI share the same permanent regression gate so package validation cannot silently omit the historical upgrade path or standalone release regressions.
- Release JARs and ZIP archives are rebuilt deterministically and compared byte-for-byte before publication. Extracted Linux packages and Windows launchers are smoke-tested before a tagged release can publish.

## Compatibility

StarChem v1.8.0 multiplayer uses **protocol 17** and requires matching **rules version 27** and packaged configuration fingerprint. Published StarChem v1.7.0 clients and servers use protocol 8 and are intentionally incompatible with v1.8.0 multiplayer.

A v1.7.0 server-data directory can be upgraded to v1.8.0, but once v1.8.0 has migrated and written current state, rollback must use the pre-upgrade backup rather than pointing the older binary at files written by v1.8.0. Read `UPGRADING_TO_1.8.0.md` before upgrading a persistent server.

## Requirements

- Java 17 or newer.
- Extract the complete release ZIP before launching.
- Windows client: `run-starchem.bat`.
- Windows dedicated server: `run-starchem-server.bat`.
- Linux client: `./run-starchem.sh`.
- Linux dedicated server: `./run-starchem-server.sh`.
