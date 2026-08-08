# Issue 291: queued waypoints and compound orders

StarChem's player-issued command queue is authoritative on the solo/server simulation and bounded to 16 steps per unit.

## Input semantics

- A normal command replaces the current command chain.
- Holding Shift while issuing a supported command appends it to the current chain.
- Stop / Clear Orders removes the active step and queued tail.
- Hold Position is a replacing terminal order.
- Patrol, guard, escort, and hold are terminal queue steps; no later step may be appended after them.

Supported queued steps are move, attack, harvest, tactical orders, and explicit wormhole transit. Fleet formation offsets are resolved when each step is queued so later selection changes do not reshape an existing route.

## Authority and synchronization

Queue mutations carry the unit ID, operation, expected queue revision, and one bounded command payload. The authoritative server validates ownership, revision, queue length, finite coordinates, target/resource eligibility, tactical targets, and wormhole identity before accepting the mutation. Stale revisions do not mutate the chain and force authoritative queue state back to the owner.

Future command-chain state is synchronized only to the owning player through ordered `QUEUE_STATE` packets. It is not included in enemy snapshot data. Initial sync/reconnect sends the full owner queue state; normal sync sends dirty queue revisions and tombstones.

## Cross-system travel

Wormhole steps store stable source-system, gate, and destination-system IDs rather than only gate coordinates. A wormhole step completes only when the authoritative unit is present in the expected destination system. Later commands may target that destination system and continue there. If the required system/gate continuity is no longer valid, the remaining chain is halted rather than executing destination coordinates in the wrong system.

## Persistence and automation

Per-unit queue revision, active state, and queued commands are stored with galaxy unit save state. Save format 6 migrates older saves with empty queues. Multiplayer protocol 15 carries queue mutations and owner-only queue synchronization.

Player queue intent takes precedence over autonomous hauler routing. A queued explicit harvest also completes when that resource is depleted instead of silently retargeting another deposit before the next queued step can run.

## Validation

`validateIssue291CommandQueues` covers at least eight queued movement waypoints, replacement, stale revisions, the 16-step bound, harvest advancement, wormhole continuation, galaxy save/restore, wire round-tripping, owner-only synchronization, and oversized state rejection. The feature is additionally exercised with galaxy connectivity, save-store, network-security, and the repository-wide `check` validation.
