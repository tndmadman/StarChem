# Issue 293: inter-system logistics routes and escorted convoys

StarChem now supports persistent player-defined cargo routes between owned stations in different star systems.

## Route model

A route is server-authoritative and stores a stable route ID, owner, source system/base, destination system/base, permitted materials, per-material source reserve, per-material destination target, maximum shipment batch, priority, assigned transports, optional escorts, and pause state.

Route runtime reports `WAITING`, `LOADING`, `OUTBOUND`, `UNLOADING`, `RETURNING`, `BLOCKED`, or `PAUSED`.

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

A convoy never teleports between systems. The route controller moves the transport to the selected gate and the existing authoritative wormhole transfer path performs the system transition. The route then replans from the transport's actual system for the next hop.

If a required path or gate is unavailable, the transport stops in its current system and the route reports `BLOCKED`. Future route ticks retry path planning.

## Transports and escorts

Creation can use explicitly selected Haulers/Freighters or automatic transport assignment. Automatic assignment chooses an available empty cargo hull owned by the player and prefers higher cargo capacity.

Assigned route transports are excluded from ordinary local Hauler automation, generic cargo auto-unload, miner return handling, and idle station orbiting while the route owns them. This prevents local automation from unloading route cargo into the source or an intermediate station.

Selected armed ships can be assigned as escorts. Inside a system they use the existing persistent Escort order and retain their configured combat stance/target-priority policy. When the lead transport approaches a wormhole, the route controller moves escorts to the same gate so they transfer with the convoy; after transfer, Escort association is re-established against the lead transport.

Player intent has precedence. A manual move, attack, harvest, or tactical order releases that ship from route automation and pauses the affected route rather than fighting the player's command.

## Persistence and recovery

Route definitions and assignments are stored in the existing server runtime save alongside other world-scoped systems. Physical ship location and cargo remain in the galaxy/unit save. On restore the galaxy is loaded first, then route runtime is restored.

The route controller deliberately does not trust a saved in-transit amount. It reconciles assigned ships from their restored authoritative location and `Unit.inventory` before dispatching new cargo. This prevents restart recovery from duplicating a shipment.

If an assigned transport no longer exists, its remembered in-transit cargo is discarded from route accounting; no replacement cargo is created. Automatic routes may later claim another empty transport, while explicitly assigned routes wait for the player to edit the route.

If a source or destination station is unavailable, the route stops and reports a blocked condition. Cargo already on a surviving transport remains physical cargo.

## Multiplayer authority

Route mutations reuse the existing authenticated `PROD|...|CONTROL` command path. The server resolves the source station in the requesting player's authoritative viewed system, verifies ownership, validates the destination station in its authoritative system, validates all transport and escort ownership, bounds material/ship counts and command text, and computes all paths and inventory movement server-side.

Clients submit route preferences only. They do not select authoritative wormhole paths, reserve inventory, or advance route state.

Route status is included in the station's existing logistics status field, so reconnecting/viewing clients receive current route summaries through ordinary authoritative station snapshots without adding a parallel client-owned simulation.

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

`Issue293LogisticsRouteValidator` covers:

- real Hauler loading and physical cargo conservation;
- multi-hop wormhole convoy travel;
- destination unloading and return routing;
- source reserve enforcement across competing routes;
- destination/in-transit demand accounting;
- save/restore during an in-progress shipment with physical-cargo reconciliation;
- manual command precedence without deleting cargo;
- pause, resume, and delete lifecycle operations;
- unauthorized owner attempts;
- malformed route payloads, nonexistent systems, and bounded material lists.

The repository CI runs the issue-specific validator after compilation in addition to the normal `gradle check` and packaging workflow.
