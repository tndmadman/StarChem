# StarChem solo save/load — implementation and acceptance

This feature is developed on `feature/solo-save-load`, based on
`feature/world-lifecycle-resource-performance-20261021` (draft PR #453).
It does not change multiplayer JOIN authority. It must not merge automatically.

## User flow

- **New Solo Game** creates an independent UUID slot, launches a normal solo World, and
  schedules an initial validated save. The slot name is editable without changing its ID.
- **Continue Game** searches in a worker for the newest checksum-verified snapshot across
  all slots, including usable recovery points. If none is valid it reports that clearly.
- **Load Game** shows a scrollable slot list. **Recovery...** displays available current,
  previous, manual and timestamped archive points. Explicit recovery selections undergo
  full validation before World construction; failed load retains the current game.
- The solo pause menu offers **Save Game**, **Save As New Slot**, **Load Game**,
  **Return to Main Menu**, and **Quit Game** in addition to existing actions.
  Multiplayer retains its original controls.
- Return to menu and window close provide **Save & Exit**, **Exit Without Saving**,
  **Cancel**. Failed Save & Exit does not close StarChem; a retry/exit/cancel prompt appears.
- Autosave uses the existing default 60-second Config value and a bounded per-slot
  archive backup rotation. Manual checkpoints remain distinct from autosaves.

## Save directory and archive layout

The root is `DefaultStoragePaths.graphicalSaveDirectory()/solo/`, respecting the
configured graphical save directory. Each slot is a UUID directory containing:

| Path in slot | Purpose |
| --- | --- |
| `name.txt` | Independent editable display name. Can be reconstructed from manifest. |
| `solo-current.starchem-save` | Latest durable verified save. |
| `solo-previous.starchem-save` | Prior archive for rollback. |
| `solo-manual.starchem-save` | Latest explicit manual or initial checkpoint. |
| `solo-*.starchem-save` timestamped backups | Bounded historical snapshots via shared backup policy. |

The archive reuses **format 6** and existing `ServerSaveStore` ZIP layout:
`manifest.json`, `players.json`, `galaxy.json`, `runtime.json`. The
shared serializer, checksum verification, archive size limits, temporary-file
promotion, schema migration, and backup system remain in use.

The optional **solo manifest extension** records `archiveKind=solo-state`,
`soloSlotId`, `soloSlotName`, `soloPlayerName`, `soloStartSystemId`,
`galaxyCopies`, `galaxyGenerationSeed`, full `galaxyGeneration` settings
and `activeSystemId`, in addition to existing creation/last-save times,
build version, archive version, reason, and skirmish rules.

The regular server loader **rejects solo-state** archives and the solo loader
**rejects server-state** archives. Solo `players.sessions` is empty, avoiding
copying dedicated-server authentication sessions. IDs are UUID validated;
symlink slot entries and traversal-like names are rejected.

## Authoritative state inventory

These are the current persistence *capture paths*, not a claim that every
possible future gameplay field has been proven by a dedicated test:

| Source | Captured content |
| --- | --- |
| `players.roster` | Player ID, name, visual color, local flag |
| `players.completedResearch` | Player research completion and unlocks |
| `manifest.skirmish` | Skirmish settings, NPC policy and victory rules |
| `galaxy` / `GalaxyCoordinator.captureSave` | Active/inactive systems, contents, entities, resources, wormholes and galaxy state (shared server persistence contract) |
| `runtime.simulationScheduler` | Authoritative scheduler state |
| `runtime.productionPlanner` | Queue/planner runtime |
| `runtime.logisticsRoutes` | Logistics reservation/route runtime |
| `runtime.fleets` | Fleet state and arrangements |
| `runtime.npcFactions` | Faction runtime state |
| `runtime.npcStrategicDirector` | NPC strategic state |
| `runtime.npcStationConstruction` | NPC station construction |
| `runtime.npcExpeditions` | NPC expeditions |
| `runtime.npcExpeditionReadiness` | NPC expedition readiness |
| `runtime.npcRecovery` | NPC recovery |
| `runtime.npcRepairEvacuation` | NPC repair and evacuation |
| `runtime.npcSquadCombat` | NPC combat formations/state |
| `runtime.galaxyEvents` | Event policy, instances, system clocks, cooldowns and timers |
| `runtime.shipFits` | World-local dynamic ship-fitting catalogs |
| `runtime.soloFog` | Slot-specific explored cells and known wormholes, not render tiles |
| `runtime.soloUi` | Per-slot camera, zoom, map selection, control groups and tutorial progression |

Other entity details such as health, equipment, ship positions, cargo, mining
depletion, production, and ownership are delegated to the existing
`GalaxyCoordinator` save contract. A complete field-by-field authoritative
audit needs game-domain integration tests before a production certification.

## Threading and lifecycle

1. Solo save requests stop the simulation timer at an EDT boundary.
2. `GamePanel` and `TutorialOverlay` contribute non-authoritative view progress.
3. `SoloSaveService.capture` invokes shared `ServerSaveStore` capture and
   freezes maps into an immutable `SaveSnapshot`.
4. The game may resume. A `SwingWorker` submits the immutable snapshot to the
   existing persistence coordinator; ZIP serialization and I/O happen off the EDT.
5. Save success is announced **only after** persistence `await` completes.
   Autosave requests are coalesced; concurrent UI save/load is guarded by
   `soloBusy`. Disk failure reports an error without treating queue acceptance
   as success.
6. A candidate load is integrity-verified, restored independently, and switched
   only after preparation succeeds. A failed candidate is discarded and generation
   options are rolled back; the previous active World is retained and resumed.
7. On successful transition, menu/UI objects are rebound; previous-world disposal
   uses lifecycle cleanup. Loaded solo Worlds bypass `PeerNetwork.start` so
   the saved roster is not reset by fresh-solo initialization.

`GalaxyRuntimeOptions` is still process-global. Loading explicitly activates
the archived generation options and rolls them back on failures. World-scoped
configuration would be a preferable long-term architecture; repeated
cross-campaign load tests remain important.

## Validation

Run from the project root using **Java 17 / Gradle 8.10.2**:

```sh
gradle clean classes check jar --no-daemon
gradle validateSoloSaveLoad --no-daemon
gradle benchmarkSoloSaveLoad --no-daemon
bash validation/run-release-regressions.sh 'build/classes/java/main:build/resources/main'
```

The new validator is part of `check` and exercises slot creation, manual save,
relaunch-style restoration, research, unit count, player color, seed, UI state,
fog isolation, autosave checkpoint independence, corrupted-current fallback,
explicit recovery selection, archive-kind authority, rename, path validation,
deletion, procedural options and generation seed.

The benchmark separately captures, writes and loads **20-system** and **64-system**
procedural galaxies and reports capture, disk-write, restoration, archive-size
and post-test heap statistics. It is report-only: hosted-runner timings are not
a hard real-time responsiveness guarantee.

CI: `.github/workflows/solo-save-load.yml` is configured for branch pushes and
PRs targeting the ahead branch. Use the latest completed workflow run, not an
older green commit, as release evidence.

### Manual graphical Windows acceptance still required

On a Windows build, check the launcher, writable save path, 900×720 minimum
window size, focus/Escape, mixed procedural and legacy campaigns, Save As,
rapid click protection, a played campaign with research/production/fleet/orders,
actual wormhole travel, fog exploration isolation, pause/resume, forced
disk-full failure, recent-save corruption, reload after process restart,
and 10+ successive load transitions.

No claim of Windows manual acceptance should be made until a tester records it.

### Existing blocker

The ahead branch's draft PR #453 had one failed paired render-performance
comparison against its 20% regression gate during the baseline review.
This must be rechecked separately. The save PR should not mask or inherit
an unverified performance exception and must remain draft until required
integration acceptance is complete.
