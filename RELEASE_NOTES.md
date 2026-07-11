# StarChem v0.1.5

StarChem v0.1.5 is a major multiplayer, production, and stability update.

## Highlights

- Added persistent patrol, guard, escort, hold-position, and attack-move orders.
- Added a global multiplayer leaderboard covering every star system.
- Added full station production queues for ships, station packages, manufactured items, and research.
- Improved simulation of inactive and background star systems.
- Added automatic multiplayer reconnection and secure session recovery.

## Production and logistics

Stations now use one shared FIFO production queue. Jobs can be reordered or cancelled, funded jobs return their reserved resources when cancelled, and active work pauses when fuel, resources, or an eligible Deployer are unavailable.

Logistics requests now create real production jobs immediately. Deliveries fund the existing job without changing its queue position, and repeated requests remain separate queue entries. Queue order, progress, blocked state, reservations, and logistics state are synchronized across multiplayer clients.

## Tactical orders

Ships can now receive persistent patrol, guard, escort, hold-position, and attack-move orders. Ships temporarily respond to nearby combat and then return to their assigned order. Orders are server-authoritative and remain synchronized for late joins and reconnecting players.

## Multiplayer improvements

- Added secure reconnect tokens and automatic session recovery.
- Player ships, stations, research, queues, home systems, and view state survive temporary disconnects.
- Restarted clients can reclaim their previous session.
- Commands are blocked while reconnecting instead of being sent into a dead connection.
- Duplicate player names are rejected with a clear error.
- Fixed duplicate and placeholder player-home systems.
- Improved cross-system viewing and snapshot synchronization.
- Added stricter validation for UDP traffic, acknowledgements, packet chunks, snapshots, entity IDs, and production queues.

## Galaxy and simulation fixes

- Fixed solo wormhole network generation.
- Background systems now continue simulating correctly.
- NPC runtime state and timers are isolated per star system.
- Corsairs are restricted to the Corsair system.
- Raiders and Free Miners are scoped to their appropriate player-home systems.
- Audio now plays only for the system currently being viewed.
- Fixed local-host state leaking between server and client worlds.

## Developer and release tooling

- Added an F4 performance overlay in Dev mode.
- Added live production-timer controls to the in-game Dev Crafting panel.
- Added secure remote developer access using strong tokens or explicit host approval.
- Added host controls for granting and revoking developer access.
- Added automatic configuration, rules, production, network, snapshot, session, and galaxy validation.
- Release builds now derive their version from the Git tag, embed the commit SHA, report the version through `--version` and the window title, and verify the final package before publishing.

## Additional fixes

- Unknown ship, station, package, and rule IDs are rejected instead of silently creating fallback entities.
- Malformed production queues and snapshots are rejected atomically without partially changing the game world.
- Remote launcher input is handled through the lobby and validated safely.
- Improved dedicated-server error reporting and shutdown behavior.
- Fixed several cross-system state, audio, ownership, mining, spawning, camera, selection, and synchronization issues.

## Requirements

- Java 17 or newer.
- Multiplayer clients and servers should use StarChem v0.1.5.
