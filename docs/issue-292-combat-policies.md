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

## Authority, synchronization, and persistence

Combat policy mutations reuse the revisioned per-unit authoritative intent channel introduced for issue #291. The server resolves ownership and system location, validates enum values, rejects stale revisions, applies the policy, and marks the unit state dirty for owner-only replication.

The policy is included in initial/reconnect `QUEUE_STATE` synchronization and is persisted with each unit's command-queue state in galaxy saves. Because the policy is keyed to the unit rather than a star system, it follows the ship through wormhole transit. Legacy saved units that have no policy fields default to `AGGRESSIVE` and `NEAREST_THREAT`.

The multiplayer protocol is version 16 for this change. Rules/configuration data are unchanged.

## Point-defense exception

`HOLD_FIRE` is an offensive-fire restriction, not a strict weapon-power-down mode. Screen weapons may continue intercepting valid hostile stoppable projectiles. This lets an escort remain defensive against missiles without allowing its normal anti-ship weapons to fire.
