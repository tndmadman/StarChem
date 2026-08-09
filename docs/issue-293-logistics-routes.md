# Issue 293: inter-system logistics routes and escorted convoys

StarChem supports persistent player-defined cargo routes between owned stations in different star systems.

## Route model

A route is server-authoritative and stores a stable route ID, owner, source system/base, destination system/base, permitted materials, per-material source reserve, per-material destination target, maximum shipment batch, priority, assigned transports, optional escorts, and pause state.

Route runtime reports `WAITING`, `LOADING`, `OUTBOUND`, `UNLOADING`, `RETURNING`, `BLOCKED`, or `PAUSED`. A bounded `RouteCondition` distinguishes transient routing problems such as `NO_PATH` or `MISSING_GATE` from structural failures such as `SOURCE_UNAVAILABLE`, `DESTINATION_UNAVAILABLE`, or a removed endpoint system.

Routes are managed from the controls menu of any owned station. Production stations expose an **OPEN PRODUCTION** action in the same menu so adding logistics controls does not remove normal production access.

## Physical cargo and reservations

Inter-system routes use real `Hauler` and `Freighter` units. They do not create disposable logistics shuttles or virtual cargo.

A shipment is committed only when an assigned transport is physically at the source station. At that moment the authoritative route controller removes the loaded amount from the source hangar and adds the same amount to `Unit.inventory`.

This is the reservation boundary:

- production and other consumers can spend only the inventory that remains in the source hangar;
- route cargo cannot be double-spent because it has already left the hangar;
- competing routes are processed by descending route priority and then stable route ID;
- the configured source reserve is never loaded;
- destination demand counts cargo already loaded on every route targeting the same destination, preventing duplicate dispatches;
- a destroyed transport loses or drops its physical cargo through normal gameplay instead of causing the route to recreate it.

## Wormhole routing

The route planner performs a deterministic breadth-first search over the authoritative galaxy link graph. Each hop is resolved to the current system's real `WormholeGate` immediately before travel.

A convoy never teleports between systems during gameplay. The route controller moves the transport to the selected gate and the existing authoritative wormhole transfer path performs the system transition. The route then replans from the transport's actual system for the next hop.

If a required path or gate is temporarily unavailable, the transport stops in its current system and the route reports `BLOCKED`. Future route ticks retry path planning. When a valid path returns, the route clears the transient condition and continues from the convoy's authoritative location.

## Transports and escorts

Creation can use explicitly selected Haulers/Freighters or automatic transport assignment. Automatic assignment chooses an available empty cargo hull owned by the player and prefers higher cargo capacity.

Assigned route transports are excluded from ordinary local Hauler automation, generic cargo auto-unload, miner return handling, and idle station orbiting while the route owns them. This prevents local automation from unloading route cargo into the source or an intermediate station.

Selected armed ships can be assigned as escorts. Inside a system they use the existing persistent Escort order and retain their configured combat stance/target-priority policy. When the lead transport approaches a wormhole, the route controller records the escort's intended hop and stages the transport until assigned escorts are sufficiently assembled. If the transport crosses first, a lagging escort continues toward the persisted hop instead of stopping when the lead disappears from the local system. The hop marker is stored with the unit and therefore survives save/restart.

Player intent has precedence. A manual move, attack, harvest, or tactical order releases that ship from route automation and pauses the affected route rather than fighting the player's command.

## Persistence and recovery

Route definitions and assignments are stored in the existing server runtime save alongside other world-scoped systems. Physical ship location and cargo remain in the galaxy/unit save. On restore the galaxy is loaded first, then route runtime is restored.

The route controller deliberately does not trust a saved in-transit amount. It reconciles assigned ships from their restored authoritative location and `Unit.inventory` before dispatching new cargo. This prevents restart recovery from duplicating a shipment.

Restore enforces the same material, transport, escort, and per-player route bounds used at runtime. Duplicate saved ship assignments are reconciled deterministically by route priority and then stable route ID; the losing route releases the conflicting assignment rather than creating two owners for one ship.

If an assigned transport no longer exists, its remembered in-transit cargo is discarded from route accounting; no replacement cargo is created. Automatic routes may later claim another empty transport, while explicitly assigned routes wait for the player to edit the route.

Destroyed or missing source/destination stations and removed endpoint systems are structural failures. The route moves to `PAUSED`, surviving physical cargo remains on surviving transports, and resume is rejected until the endpoint is valid again. Temporary wormhole topology/gate failures remain `BLOCKED` and retry automatically.

Meaningful failure and recovery transitions publish bounded `LOGISTICS` notices through the existing `GameNoticeCenter`; repeated simulation ticks do not create a notice per tick.

## Multiplayer authority and reconnect

Route mutations reuse the existing authenticated `PROD|...|CONTROL` command path. The server resolves the source station in the requesting player's authoritative viewed system, verifies ownership, validates the destination station in its authoritative system, validates all transport and escort ownership, bounds material/ship counts and command text, and computes all paths and inventory movement server-side.

Clients submit route preferences only. They do not select authoritative wormhole paths, reserve inventory, or advance route state.

Route status is included in `Base.logisticsStatus`. `BaseState` now carries that bounded status through normal authoritative snapshots, `NetBaseSync` restores it on the client, and the station controls reconstruct `RouteView` data from it when no server route runtime exists locally. This closes the real reconnect/view-resync path rather than relying on a same-world fallback. Because the base snapshot wire schema changed, multiplayer protocol version is 17.

## Controls

From an owned station:

1. Open the station controls menu.
2. Choose **CREATE ROUTE**.
3. Pick a known destination system and enter the owned destination base ID.
4. Enter one or more material IDs separated by commas.
5. Set source reserve, destination target, batch size, and priority.
6. Optionally preselect one or more Haulers/Freighters and armed escorts before opening the editor. With no selected transport, a new route uses automatic assignment.
7. Existing routes expose Pause/Resume, Edit, and Delete actions.

When editing, leaving transports/escorts unselected keeps their current assignments. The edit dialog can explicitly clear escort assignments.

## Validation

`Issue293LogisticsRouteValidator` plus its completion suite cover:

- real Hauler loading and physical cargo conservation;
- multi-hop wormhole convoy travel, including a natural non-teleported simulation pass;
- lagging escort hop persistence and save/restart recovery;
- destination unloading and return routing;
- source reserve enforcement across competing routes;
- production-vs-route inventory contention;
- destination/in-transit demand accounting;
- destroyed transport cargo loss without source refund/recreation;
- source and destination destruction behavior and resume guards;
- temporary local gate loss plus authoritative saved-topology removal/recovery;
- removed destination-system recovery behavior;
- save/restore coverage across `WAITING`, `LOADING`, `OUTBOUND`, `UNLOADING`, `RETURNING`, `BLOCKED`, and `PAUSED`;
- real `SnapshotWriter`/`SnapshotReader`/`WorldNetAccess` reconnect-resync of route state;
- inactive-system scheduler wakeup for a moving convoy;
- manual command precedence without deleting cargo;
- pause, resume, edit, and delete lifecycle operations;
- unauthorized source/destination/transport/escort attempts and unknown ship IDs;
- maximum route/material/transport/escort counts, oversized commands, malformed payloads, and malformed saved route state;
- deterministic restored assignment-conflict reconciliation.

The repository CI runs the issue-specific validator after compilation in addition to the normal `gradle check` and packaging workflow.
