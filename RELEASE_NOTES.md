# StarChem v1.8.2

StarChem v1.8.2 is a multiplayer join/reconnect hotfix for the v1.8 line. It keeps **protocol 17**, **rules version 27**, and **save format 6** unchanged, so v1.8.1 saves continue to load directly. Multiplayer still requires the exact application version, so servers and clients should update to v1.8.2 together.

## v1.8.2 Multiplayer Hotfix

- Prevented owner-scoped galaxy/strategic state from mutating client state during generic packet decoding before the client identity has been validated.
- Ignore stale or wrong-owner galaxy projections during join/reconnect instead of repeatedly rejecting strategic summaries against the current local player.
- Bind accepted server packets to the current TCP connection identity in addition to the configured endpoint, hardening reconnects against stale connection frames.
- Reuse an already-derived in-memory server-scoped credential when the same verified TLS identity and salt re-challenge during a transient bootstrap retry, avoiding unnecessary password re-entry.
- Improved malformed server-packet diagnostics to retain the exception detail and type instead of logging only `IllegalStateException`.
- Added galaxy-wire regression coverage proving decode no longer applies owner-scoped strategic state as a side effect.

## v1.8.1 Changes Carried Forward

StarChem v1.8.1 is the fleet-scalability, manufacturing-workflow, multiplayer-stability, and narration reliability update to the v1.8 release line. It is built from the published v1.8.0 baseline plus the completed work for moving-fleet fog/intel performance (#372), production-policy WAN desynchronization (#371), the centralized Manufacturing Command interface (#376), and narration backend reliability (#377).

The focus of this release is not a new save or rules generation. It makes the large systems introduced in v1.8.0 substantially cheaper to run, easier to control, and safer to use over real multiplayer connections.

## Highlights

- Added the global **F9 Manufacturing Command** interface for ships, materials, station packages, research, queues, and production policies.
- Fixed the severe moving-fleet fog/intel CPU scaling regression that could collapse client FPS while the GPU remained mostly idle.
- Fixed production-policy/template server hotspots that could cause remote clients to stall and rubber-band after automation was enabled.
- Added broad large-fleet spatial indexing, culling, render LOD, simulation cadence, and performance instrumentation work.
- Reworked large-selection presentation into a consolidated HUD summary instead of expensive per-ship selection decoration.
- Fixed Windows narration backend detection and made narration explicitly opt-in/default-off.

## Manufacturing Command And Production UI

Manufacturing is now controlled through one in-game command surface instead of requiring the player to open individual station production/policy windows.

- Added a global **F9 Manufacturing** overlay available from normal gameplay.
- Added F9 Manufacturing to the standard top-left command hints.
- Combined ships, manufactured materials, station packages, and research into one searchable catalog.
- Added **AUTO** routing to compatible owned stations.
- Added manual station override when a specific production station should be used.
- Added bulk queueing and live cross-station queue management.
- Embedded maintain-stock, maintain-fleet, and repeat production-policy creation directly in the same interface.
- Embedded production-policy browsing, templates, reserve floors, recovery, create/edit, priority, pause/resume, and deletion.
- Removed the standalone operating-system/Swing production-policy manager from normal gameplay; compatibility entry points redirect into the F9 in-game policy view.
- Added recipe-aware inventory information for the selected output, organized by system, station, and required material.
- Added separate current-system and known remote-system owned inventory presentation.
- Added material icons, quantities, and direct recipe-shortfall reporting.
- Remote-system stock remains informational; authoritative logistics and resource-routing rules are unchanged.
- Station production and production-policy actions now route into Manufacturing Command.
- Added an industrial bronze/graphite/green/rust/plum presentation with semantic production progress indicators.

### Production correctness

- Exact ship hull IDs are now resolved before authored/runtime loadout IDs, preventing a colliding loadout identifier from changing what hull a finite production request builds.
- Existing authoritative `PROD`, `ProductionCommands`, `BuildSystem`, research, inventory, logistics, and queue paths remain the source of truth behind the new UI.

## Production Policy And WAN Multiplayer Stability

Issue #371 identified a v1.8.0 server hotspot where enabling production policies/templates could immediately disturb authoritative update cadence on a real WAN connection. The visible result was ships appearing to stop and then rubber-band forward as snapshots caught up.

v1.8.1 changes the policy path so normal automation does substantially less work between actual policy evaluations:

- Removed unnecessary production-policy status refreshes on simulation ticks where no policy evaluation is due.
- Added an indexed evaluation view so linked jobs and waiting-state checks are not repeatedly rescanned for each policy decision.
- Captures the galaxy supply ledger once per policy evaluation instead of repeatedly rebuilding equivalent state.
- Added atomic/batched starter-template policy creation so multi-policy starters do not perform a full refresh after every individual policy mutation.
- Reduced temporary production-queue scanning when locating newly created jobs.
- Added live-system galaxy state access for read-only supply queries without forcing save-format serialization.
- Added lightweight production-policy evaluation, refresh, and ledger timing counters for regression and host diagnostics.

These changes are intended to keep server tick cadence and snapshot delivery stable when miner replacement or other standing production automation becomes active.

## Fog Of War, Radar, And Moving-Fleet Performance

Issue #372 traced the v1.8.0 moving-fleet slowdown primarily to movement-driven fog/intel invalidation and repeated sensor discovery. On affected hardware, moving/orbiting fleets could saturate a small number of CPU threads while leaving the GPU mostly idle.

v1.8.1 substantially reworks this hot path:

- Added stable identities for visibility sensors so ordinary movement no longer makes the complete sensor set appear globally replaced.
- Made visibility frames actually reuse captured sensor data across unit, base, resource, point, and target detection queries.
- Added incremental per-sensor current-fog coverage updates.
- Added localized dirty-region/world-space fog updates instead of broad viewport recomposition for routine sensor movement.
- Preserved exact movement/detection semantics rather than solving the problem by coarse position rounding.
- Reused optimized visibility data in snapshot filtering and other server-authoritative visibility paths.
- Added dedicated fog-performance regression coverage, including moving-fleet scaling checks.

The intended result is that continuously moving fleets are much closer in cost to equivalent stationary fleets instead of causing catastrophic fog-related frame loss.

## World Spatial Indexing And Simulation Scaling

The #372 work expanded beyond fog itself to remove several fleet-size hot paths that would otherwise become the next bottlenecks.

- Added a shared world spatial index for fleet-scale proximity and candidate queries.
- Spatially indexed and staggered applicable combat work.
- Added allocation-conscious spatial render-candidate caching.
- Integrated spatial candidate selection into world rendering hot paths.
- Added viewport/offscreen culling so detailed rendering work can be skipped for entities outside the tactical view.
- Added indexed tackle lookups instead of repeatedly scanning all units for every moving ship.
- Reduced repeated module-definition resolution/allocation in movement/combat hot paths.
- Added cadenced simulation scheduling for work that does not need to run at the maximum frame/update rate.
- Added bounded worker infrastructure for safe immutable performance jobs.
- Reduced strategic NPC work frequency where full-rate processing is unnecessary.
- Added prediction/spatial-state profiling to identify remaining hot paths.

## Fleet Rendering And Large Selections

Large fleet selections now prioritize tactical readability and bounded draw cost.

- Added a consolidated **Selection Summary HUD** for selected fleets.
- Removed per-ship selected-fleet world-space names/HP/shield/selection decoration from the large-selection path.
- Removed large-selection order geometry that previously scaled with the selected fleet.
- Added fleet-scale ship sprite caching/LOD work.
- Added dedicated selection performance validation.
- Added additional render-candidate and selection tracing counters.

### Tactical zoom change

The tactical camera maximum zoom is intentionally capped at approximately **0.712** in v1.8.1, down from the previous 2.2 maximum. Legacy/restored camera state is clamped to the new range. This is part of the fleet-scale presentation/performance model, not a mouse-wheel failure.

## Multiplayer Snapshot And Client Work

The performance pass also reduces duplicated client/server work around authoritative updates:

- Added snapshot batch caching/reuse in applicable peer synchronization paths.
- Reduced repeated visibility work during owner-scoped snapshot filtering.
- Added additional client-prediction profiling and spatial-state refresh handling.
- Extended performance telemetry around snapshot decode/apply and related update paths.

The existing bounded/coalesced transport model remains in place; this release targets the expensive simulation, visibility, production, and rendering work that could delay otherwise bounded snapshot delivery.

## Performance Diagnostics And Regression Gates

v1.8.1 adds significantly more visibility into where CPU time is going:

- Added subsystem performance tracing.
- Added fog state/sensor/update counters.
- Added movement-path performance profiling.
- Added per-thread CPU timing in the developer performance overlay.
- Added large-selection performance counters.
- Added dedicated fog and selection performance validators.
- Added fleet-scaling acceptance gates for the #372 regression.
- Added narration process validation to canonical release regressions.

## Narration / Text-To-Speech

- Fixed Windows PowerShell/System.Speech backend detection.
- The Windows backend probe now validates System.Speech itself rather than attempting an unsupported `--version` check.
- Narration backend/process failures are reported instead of being silently discarded.
- Narration is **disabled by default** and remains opt-in.
- Migrated the persisted enable preference to a new key so an old `enabled=true` value cannot unexpectedly make the updated client start speaking.
- Explicit user choices made after the migration continue to persist normally.
- Added bounded narration process I/O handling and timeout/descendant cleanup regression coverage.
- Added Windows-specific narration validation in CI.

## Compatibility And Upgrade Notes

StarChem v1.8.1 remains on the v1.8 compatibility generation:

- multiplayer **protocol 17**;
- **rules version 27**;
- dedicated-server **save format 6**;
- Java 17 or newer.

### v1.8.0 saves

There is no new save-format migration between v1.8.0 and v1.8.1. Existing v1.8.0 persistent server data remains save format 6 and is intended to load directly under v1.8.1.

Back up the complete server-data directory before changing the binary anyway. The backup must include the save plus TLS identity, authentication/session state, moderation/admin data, observation/activity state, and other companion files.

### Multiplayer version matching

**v1.8.0 and v1.8.1 clients/servers do not interoperate in multiplayer even though protocol, rules, and save-format numbers are unchanged.** StarChem's multiplayer compatibility handshake also requires an exact matching application version. Update the server and all connecting clients to v1.8.1 together.

Different build commits of the same v1.8.1 application version may still be accepted when protocol, application version, rules version, and packaged configuration fingerprint match.

### Upgrading from v1.7.0

The existing `UPGRADING_TO_1.8.0.md` procedure remains the required migration path for a persistent server coming from published v1.7.0 because v1.8.1 still uses the v1.8 save/protocol generation. Do not skip the full pre-upgrade backup.

## Release Validation

The v1.8.1 release candidate is expected to pass the existing v1.8 release wall, including:

- clean client and dedicated-server startup;
- simultaneous multiplayer clients, reconnect, session recovery, and persistence recovery;
- real published-v1.7.0 migration into current save format;
- cross-system production sourcing and physical logistics;
- production policies and recovery;
- ship fitting/refitting and atomic resource handling;
- command queues and combat/radar policies;
- dynamic event lifecycle and save/reload;
- wormhole connectivity and fog-of-war visibility;
- observer authority isolation;
- NPC cross-system/strategic stability checks;
- diplomacy/objective/victory behavior;
- dedicated-server shutdown/save;
- sustained TCP soak;
- deterministic JAR/ZIP reproduction;
- Linux, Windows, and macOS package/launcher smoke tests;
- Windows narration process/backend validation.

## Requirements

- Java 17 or newer.
- Extract the complete release ZIP before launching.
- Windows client: `run-starchem.bat`.
- Windows dedicated server: `run-starchem-server.bat`.
- Linux client: `./run-starchem.sh`.
- Linux dedicated server: `./run-starchem-server.sh`.

---

# StarChem v1.8.0

v1.8.0 was the major gameplay, multiplayer, fleet-control, industry, intelligence, persistence, and server-operations release that introduced the protocol 17 / rules version 27 / save format 6 generation. Its major additions included server-authoritative fog of war and radar/intelligence, physical inter-system logistics and cross-system production sourcing, standing production policies, fitting/refitting, diplomacy/chat/observation, dynamic galaxy events, strategic empire management, tutorials/settings, and the validated v1.7.0 persistent-server migration path.

v1.8.1 is a patch on that generation and supersedes v1.8.0 for multiplayer deployment.
