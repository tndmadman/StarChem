# Issue 292: Combat stances and target-priority policies

StarChem ships now expose a persistent server-authoritative combat policy. The policy is independent of tactical movement orders, so a ship can remain on patrol, guard, escort, hold, attack-move, or a queued route while retaining its chosen combat behavior.

## Stances

- **Aggressive** is the default and most closely matches previous automatic combat behavior. The ship acquires valid visible hostiles and may pursue them inside a bounded leash. Tactical orders continue to define their existing guard, escort, patrol, attack-move, or hold geometry; an idle ship uses an engagement anchor captured when automatic combat begins.
- **Defensive** automatically reacts to immediate threats and especially to enemies attacking the ship or its guarded/escorted target. Pursuit is deliberately tighter than Aggressive and remains bounded by the active tactical order.
- **Passive** never acquires a new target automatically. Explicit player attack commands still work as a temporary override. When that target is destroyed or becomes invalid, the ship returns to Passive behavior.
- **Hold Fire** suppresses offensive anti-ship/anti-structure firing and automatic target acquisition. An explicit target is retained so firing can resume predictably when Hold Fire is released. Point-defense interception remains enabled.

## Target priorities

Target priorities only rank targets that are already legal: the server still requires a living hostile contact that is visible to the owner and allowed by diplomacy and the active tactical-order leash.

- **Nearest Threat** favors contacts threatening the ship or protected target, then distance.
- **Protect Assigned** strongly favors enemies attacking a guarded or escorted target.
- **Screening** favors small craft and makes point defense prioritize projectiles aimed at the guarded/escorted target, then the screening ship itself.
- **Combat First** favors armed combat ships.
- **Workers / Logistics First** favors harvesters, cargo/logistics, salvage, deployment, and non-combat scout roles.
- **Structures First** ranks structures ahead of ships.
- **Structures Last** ranks ships ahead of structures.

Within the selected policy bucket, existing preferences for actively firing contacts and high-value jammer/radar structures are retained.

## Player controls

When one or more local ships are selected, two compact controls appear beneath the main HUD:

- `STANCE: ...`
- `TARGET: ...`

Left-click cycles forward. Shift-left-click cycles backward. Mixed selections display `MIXED`; choosing a value applies that policy to the selected ships.

## Radar combat coordination

Radar stations now use the same authoritative combat-policy layer to coordinate local combat responses instead of directly replacing ship orders.

- A radar only commands ships owned by the radar's owner. Shared allied intel can contribute contacts, but it never grants command authority over an ally's ships.
- Ships explicitly **Guarding the radar** are preferred responders. If response capacity remains, truly idle armed ships owned by the same player may also be dispatched.
- Radar Picket I, Radar Array II, and Radar Nexus III retain their configured response capacities and radii. Multiple radars claim responders deterministically, with higher-tier stations evaluated first so one ship is not simultaneously controlled by several radars.
- **Passive** and **Hold Fire** ships are never automatically dispatched by radar.
- **Defensive** ships respond only to hostile units that are actively threatening the radar owner's assets.
- **Aggressive** ships can intercept any valid hostile radar contact inside the station's response envelope.
- The responder's configured target-priority policy is used when the radar has several possible contacts. Combat First, Workers / Logistics First, Screening, Protect Assigned, and structure preferences therefore affect radar dispatch as well as ordinary autonomous acquisition.
- An **identified** hostile can be assigned as an automatic attack target. Radar-directed pursuit uses the radar's bounded response radius instead of destroying the ship's Guard order or forcing it through the ordinary local acquisition leash.
- A merely **classified** contact is never converted into a precise attack command. Idle responders can investigate an uncertainty-offset position instead. Guard responders remain at their protected radar until the contact is identified.
- Aggressive idle responders may investigate a recent last-known radar contact for a short period after direct detection is lost. Classified contacts retain uncertainty rather than leaking exact hidden coordinates.
- Radar assignments are temporary. If the target dies, becomes invalid, leaves the response envelope, the radar is destroyed/disabled, the ship changes stance, or the player issues an explicit command, the radar releases the assignment without erasing the ship's persistent tactical order.

This makes radar stations local command-and-control structures as well as sensors: they can coordinate miners through the existing resource-dispatch behavior and coordinate combat ships through bounded combat-response behavior.

## Authority, synchronization, and persistence

Combat policy mutations reuse the revisioned per-unit authoritative intent channel introduced for issue #291. The server resolves ownership and system location, validates enum values, rejects stale revisions, applies the policy, and marks the unit state dirty for owner-only replication.

The policy is included in initial/reconnect `QUEUE_STATE` synchronization and is persisted with each unit's command-queue state in galaxy saves. Because the policy is keyed to the unit rather than a star system, it follows the ship through wormhole transit. Legacy saved units that have no policy fields default to `AGGRESSIVE` and `NEAREST_THREAT`.

Radar response assignments are runtime automation state rather than persistent player orders. The underlying ship stance, target priority, Guard/Escort/Patrol order, and queued command state remain authoritative and persistent; radar simply applies and releases temporary automatic combat intent while the station and contact remain valid.

The multiplayer protocol is version 16 for this change. Rules/configuration data are unchanged.

## Point-defense exception

`HOLD_FIRE` is an offensive-fire restriction, not a strict weapon-power-down mode. Screen weapons may continue intercepting valid hostile stoppable projectiles. This lets an escort remain defensive against missiles without allowing its normal anti-ship weapons to fire.
