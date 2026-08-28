# StarChem Consolidation / Integration Log

This is the durable audit log for `integration/starchem-functional-2026-08-27`. Update it whenever a source branch is incorporated, a conflict is resolved, validation changes, or a material feature decision is made.

## Base

- Repository: `tndmadman/StarChem`
- Main at start of consolidation: `6402841f0d0b2beed73b6fc1094ff96cac2edaae`
- Integration branch: `integration/starchem-functional-2026-08-27`
- Integration branch was created from dynamic-event head `6747e83fdc63005e245b90b79595e5df63b16fb3`, which itself is directly ahead of the starting `main` by 20 commits.
- No source branches are deleted by this process.

## Source branch inventory

### `feature/dynamic-galaxy-events`

Tip at freeze: `6747e83fdc63005e245b90b79595e5df63b16fb3`

Status: INCLUDED AS INTEGRATION BASE.

Purpose:
- issue #297 dynamic galaxy events;
- data-driven `config/events.json`;
- deterministic server-authoritative event director;
- discovery/FOW-gated materialization;
- rich resources and derelict salvage;
- distress and pirate encounters;
- temporary system modifiers;
- unstable wormholes;
- event projection on galaxy state, minimap, and galaxy map;
- event runtime persistence through scheduler state;
- safe deterministic placement, system-role eligibility, minimum age, and cooldowns;
- dedicated event validation workflow.

Validation at freeze: all workflows on the tip were green, including CI, Java CI, Galaxy Event Validation, Observer Session Validation, AI Validation, AI Stress, Windows Observation Store, and Release StarChem.

Known remaining #297 completeness work is tracked below; the existing event implementation is not to be discarded while completing it.

### `feature/issue-296-observer-sessions`

Tip: `c7a575d34a7a1ab30efecab35b40ec484fc50e44`

Status: CONTENT ALREADY REPRESENTED IN `main` THROUGH #357. DO NOT RE-APPLY AS A SECOND FEATURE COPY.

Evidence:
- branch tip tree: `a15e957605c2f6c6f3d2c259d8dc70dd686781ad`;
- starting `main` tree: `a15e957605c2f6c6f3d2c259d8dc70dd686781ad`.

The different commit history is a squash/merge-history artifact, not missing content.

Goal to preserve:
- authenticated read-only observer sessions;
- separate observer admission/slot policy;
- PUBLIC / PLAYER_FOLLOW / FULL visibility modes;
- defeated-player conversion;
- reconnect persistence;
- permanent observer UI indication;
- observer mutation/security regressions.

### `feature/issue-298-empire-overview`

Tip: `60ea4177e7d4987af198b57683894b1faeae1b13`

Status: UNIQUE WORK TO INTEGRATE.

Compared with starting `main`: 10 branch commits ahead and 2 main commits behind from merge base `d9d04608b3ab5dae2687f595dbf5d620f585733c`.

Unique/changed files identified by compare:
- `EmpireOverviewOverlay.java` (new)
- `StrategicSummary.java` (new)
- `Issue298EmpireOverviewValidator.java` (new)
- `GalaxyMapWire.java`
- `GalaxyMapWireValidator.java`
- `GameCamera.java`
- `GameClient.java`
- `WorldRuntimeCleanup.java`

Important integration risk: `GalaxyMapWire.java` is also modified by dynamic galaxy events. The combined file must preserve both strategic-summary projection and event projection.

Goal to preserve:
- owner-scoped strategic summary;
- systems/fleets/stations/production/research overview;
- search/filter/sort/navigation behavior;
- throttled/cached aggregation rather than frame-rate galaxy scans;
- multiplayer ownership/visibility security;
- reconnect and stale-entry cleanup.

### `perf/salvage-fow-331`

Tip: `70a1fca5a53e22dc1618181258ba7e3e86ee8e15`

Status: UNIQUE PERFORMANCE WORK TO INTEGRATE.

Compared with starting `main`: 3 commits ahead and 3 behind from merge base `26087e59d91390fa9ccae67a46508cdaff72c51a`.

Changed files identified by compare:
- `FogOfWarPersistence.java`
- `FogOfWarView.java`
- `ScoutSystem.java`
- `StationControlValidator.java`

Goal to preserve:
- reuse immutable visibility detection frames;
- avoid repeated jammer/sensor work;
- squared-distance hot paths;
- shared miner-assignment counts;
- cached client fog composition;
- skip unnecessary visibility-grid rebuilds;
- remove avoidable contact-list copies;
- coalesced FOW persistence debounce work.

Important integration risk: `ScoutSystem.java` and `StationControlValidator.java` also receive #320 changes.

### `fix/issue-320-radar-wormhole-search`

Tip: `48f3fe252dcc8adf5dcda7908e61132bd592a27a`

Status: UNIQUE WORK TO INTEGRATE.

Compared with starting `main`: 5 commits ahead and 4 behind from merge base `76e8131a9a3a51a302033ef997c9075d4f87d7c9`.

Changed files identified by compare:
- `ScoutSystem.java`
- `StationControlMenu.java`
- `StationControlValidator.java`
- `StationControls.java`
- `VisibilityRules.java`

Goal to preserve:
- radar option to focus on finding/revealing wormholes;
- remembered wormhole visibility behavior remains correct;
- radar miner dispatch pauses appropriately during wormhole search;
- no FOW information leak.

Important integration risk: overlaps FOW-performance work in `ScoutSystem.java` and `StationControlValidator.java`. Dynamic event discovery consumes `VisibilityRules`, so visibility regressions must also be checked against #297.

### `fix/issue-320-remembered-wormhole-rendering`

Tip: `eb7d7bfd4ed1cbd7a95e95076892f9613cf682ec`

Status: ALREADY CONTAINED BY `main`; branch is 0 commits ahead and 5 commits behind starting `main`.

Goal to preserve:
- once discovered, remembered wormholes continue to render under FOW rules.

No content should be re-applied unless a later regression proves that main lost the behavior.

### `fix/issue-319-queued-deployer-package`

Tip: `706fe64dd90178588a1db178bb0b3a817c63491b`

Status: UNIQUE FIX TO INTEGRATE.

Compared with starting `main`: 2 commits ahead and 9 behind from merge base `b2fe3cf7cf8bc88a543743bf151d5ade93bbcfc3`.

Changed files identified by compare:
- `ProductionSystem.java`
- `ProductionQueueValidator.java`

Goal to preserve:
- station/deployer package jobs queued behind ships such as loaders remain queued and execute rather than being invalidated;
- regression coverage remains in normal verification.

## Consolidation order

The initial order is chosen to reduce semantic conflicts:

1. Dynamic events as base (already done).
2. Strategic empire overview (#298), resolving `GalaxyMapWire` by retaining both strategic-summary and event rows.
3. FOW performance (`perf/salvage-fow-331`).
4. Radar wormhole search (#320), resolving Scout/validator overlap while retaining performance optimizations.
5. Production queue/deployer fix (#319).
6. Re-audit observer and remembered-wormhole behavior as already represented by `main`.
7. Complete remaining dynamic-event acceptance work on this integration branch only.
8. Run the combined validation wall.

## No-delete / no-regression rule

A merge conflict is not permission to choose one branch wholesale and drop the other. For every conflict:

1. identify both behaviors;
2. identify whether one branch contains a newer version of the same behavior or a truly separate feature;
3. preserve both when compatible;
4. add or retain regression coverage for both;
5. document the resolution in this file;
6. ask for explicit approval before intentionally removing an already-implemented behavior.

## Dynamic event (#297) remaining completeness checklist

The integration base is functional and green, but these acceptance items still require completion or deeper proof before #297 can be called fully done:

- [ ] explicit persisted event-entity ownership roles/metadata beyond raw owned-ID sets;
- [ ] deterministic completion rewards where applicable;
- [ ] exactly-once reward generation/claim behavior across restart and contention;
- [ ] event NPC isolation from unrelated normal faction population/economy lifecycle;
- [ ] event-specific encounter ordering for distress civilians / attackers;
- [ ] safe unstable-wormhole transit draining for ships already touching/committed when collapse begins;
- [ ] owner-aware effective topology so strategic/logistics routing can use a discovered temporary shortcut without leaking it to other players;
- [ ] route recalculation/blocking when a temporary shortcut closes;
- [ ] session/operator event enable/frequency/category controls;
- [ ] actual save/reload tests for exact spawned entities and reward state, not only runtime map shape;
- [ ] explicit every-phase transition tests;
- [ ] simultaneous multiplayer discovery/alliance/no-leak tests;
- [ ] system prune/delete tests for event sources, targets, entities, and closing wormholes;
- [ ] malformed event-configuration regression matrix;
- [ ] large-galaxy bounded-work/performance regression;
- [ ] add event validation to normal Gradle `check` or an aggregate Gradle verification task;
- [ ] Codex/general event documentation;
- [ ] final current-system event HUD / richer discovered-event status presentation if required to satisfy #297 UI scope.

## Validation log

### Before consolidation

`feature/dynamic-galaxy-events` tip `6747e83fdc63005e245b90b79595e5df63b16fb3`:
- CI: PASS
- Java CI: PASS
- Galaxy Event Validation: PASS
- Observer Session Validation: PASS
- AI Validation: PASS
- AI Stress: PASS
- Windows Observation Store: PASS
- Release StarChem: PASS

### Combined branch

Pending source-branch integration. Update this section after every merge/conflict resolution and after the final PR-to-main validation run.

## Conflict / decision log

No source-branch merges have been performed yet. Entries will be added here as the consolidation proceeds.
