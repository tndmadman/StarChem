# StarChem Consolidation / Integration Log

This is the durable audit log for `integration/starchem-functional-2026-08-27`. Update it whenever a source branch is incorporated, a conflict is resolved, validation changes, or a material feature decision is made.

## Base

- Repository: `tndmadman/StarChem`
- Main at start of consolidation: `6402841f0d0b2beed73b6fc1094ff96cac2edaae`
- Integration branch: `integration/starchem-functional-2026-08-27`
- Integration branch was created from dynamic-event head `6747e83fdc63005e245b90b79595e5df63b16fb3`, which itself is directly ahead of the starting `main` by 20 commits.
- No source branches are deleted by this process.
- Branch-history comparisons are treated as advisory only. Squash merges and later consolidation commits can make a historical branch appear "ahead" even when its final file content is already present. Blob SHA/content equality and explicit behavior checks are the authoritative consolidation evidence.

## Source branch inventory and disposition

### `feature/dynamic-galaxy-events`

Tip at freeze: `6747e83fdc63005e245b90b79595e5df63b16fb3`

Status: **INCLUDED AS INTEGRATION BASE.**

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

Known remaining #297 completeness work is tracked below. Existing event behavior is preserved while that work is completed on the integration branch.

### `feature/issue-296-observer-sessions`

Tip: `c7a575d34a7a1ab30efecab35b40ec484fc50e44`

Status: **ALREADY REPRESENTED IN `main` THROUGH #357. NO RE-APPLICATION NEEDED.**

Evidence:
- branch tip tree: `a15e957605c2f6c6f3d2c259d8dc70dd686781ad`;
- starting `main` tree: `a15e957605c2f6c6f3d2c259d8dc70dd686781ad`.

The different commit history is a squash/merge-history artifact, not missing content.

Goal preserved:
- authenticated read-only observer sessions;
- separate observer admission/slot policy;
- PUBLIC / PLAYER_FOLLOW / FULL visibility modes;
- defeated-player conversion;
- reconnect persistence;
- permanent observer UI indication;
- observer mutation/security regressions.

### `feature/issue-298-empire-overview`

Tip: `60ea4177e7d4987af198b57683894b1faeae1b13`

Status: **ALREADY REPRESENTED BY CONTENT; CURRENT INTEGRATION IS A SUPERSET WHERE GALAXY WIRE OVERLAPS DYNAMIC EVENTS.**

A raw history comparison reports this branch as ahead/diverged, but content-level verification shows the #298 implementation is already present. Examples:
- `EmpireOverviewOverlay.java`: source and integration blob `115a4c088a537f893b2e6add1f8bfde1f9293b9f`;
- `StrategicSummary.java`: source and integration blob `a367adc088a1dee8af81ba8be6aaa7950df7778e`;
- `Issue298EmpireOverviewValidator.java`: source and integration blob `8d9cecbd5f9e9e66ee82bbfb67da01fd69c3e92d`.

`GalaxyMapWire.java` on the integration branch intentionally supersedes the older #298 branch version. It retains the strategic summary `E` projection/registry behavior **and** adds dynamic-event `V` rows plus owner-scoped temporary event wormhole links. Replacing it wholesale with the historical branch would regress #297.

Goal preserved:
- owner-scoped strategic summary;
- systems/fleets/stations/production/research overview;
- search/filter/sort/navigation behavior;
- throttled/cached aggregation rather than frame-rate galaxy scans;
- multiplayer ownership/visibility security;
- reconnect and stale-entry cleanup.

A temporary audit PR, #359, was opened before the content-level verification. It is not required for code transfer because the content is already present; it should be closed with an audit note, not merged.

### `perf/salvage-fow-331`

Tip: `70a1fca5a53e22dc1618181258ba7e3e86ee8e15`

Status: **ALREADY REPRESENTED BY CONTENT. NO RE-APPLICATION NEEDED.**

Blob equality evidence:
- `FogOfWarPersistence.java`: `4e5d2daf150d303f8a284f0aff7fbe4015848992` on both source and integration;
- `FogOfWarView.java`: `270558d9c1b78ad0ca4c722055c1790a6a5a402c` on both;
- `ScoutSystem.java`: `6820361e9be95fad7485fe05758af64086a7298b` on both perf source and integration;
- `StationControlValidator.java`: `6ecedb15b8614c7a930ad847c37b46999deab5f0` on both perf source and integration.

Goal preserved:
- immutable visibility-frame reuse;
- reduced repeated jammer/sensor work;
- squared-distance hot paths;
- shared miner-assignment counts;
- cached client fog composition;
- skipped unnecessary visibility-grid rebuilds;
- reduced contact-list allocation;
- coalesced FOW persistence debounce work.

### `fix/issue-320-radar-wormhole-search`

Tip: `48f3fe252dcc8adf5dcda7908e61132bd592a27a`

Status: **ALREADY REPRESENTED; CURRENT SCOUT/VALIDATOR FILES ARE INTEGRATION SUPERSETS THAT PRESERVE BOTH #320 AND THE LATER FOW PERFORMANCE WORK.**

Direct blob equality for non-overlap files:
- `StationControls.java`: `adc92ee588a1049b2e40ea7d34b275e260ef65e2` source/integration;
- `StationControlMenu.java`: `fe9e2db408c785c3a4d9ee026d7433c45b62d07f` source/integration;
- `VisibilityRules.java`: `4c51721e65e889d2dcba421d991d46362a7f4101` source/integration.

`ScoutSystem.java` differs from the old #320 branch because the integration version also contains the later FOW/miner optimization. Behavior verification confirms the #320 changes remain:
- radar resource retargeting skips radars configured for `WORMHOLES`;
- worker dispatch returns immediately while a radar is in `WORMHOLES` search mode;
- the same methods retain shared assignment counts, a reused visibility frame, and squared-range checks.

`StationControlValidator.java` is likewise a superset. It validates both shared miner-assignment optimization and authoritative radar wormhole search, including ownership rejection, invalid-mode rejection, focused aperture behavior, resource non-leakage, persisted remembered wormhole discovery, and return to AREA scanning.

Goal preserved:
- radar option to focus on finding/revealing wormholes;
- remembered wormhole visibility behavior;
- radar miner dispatch pause while wormhole search is active;
- no FOW information leak.

### `fix/issue-320-remembered-wormhole-rendering`

Tip: `eb7d7bfd4ed1cbd7a95e95076892f9613cf682ec`

Status: **ALREADY CONTAINED BY `main`. NO RE-APPLICATION NEEDED.**

Git compare result: 0 commits ahead, 5 behind starting `main`, no unique files.

Goal preserved:
- once discovered, remembered wormholes continue to render under FOW rules.

### `fix/issue-319-queued-deployer-package`

Tip: `706fe64dd90178588a1db178bb0b3a817c63491b`

Status: **ALREADY REPRESENTED BY CONTENT. NO RE-APPLICATION NEEDED.**

Blob equality evidence:
- `ProductionSystem.java`: source and integration blob `633d317cd3fcf95e8c69ce10d634632b63d6e68f`;
- `ProductionQueueValidator.java`: source and integration blob `f0e7cb4bea58ee3a897b0e4d1010ad97c2303797`.

Goal preserved:
- station/deployer package jobs queued behind ships such as loaders remain queued and execute rather than being invalidated;
- regression coverage remains present.

## Consolidation result for the screenshot branch set

All seven source branches shown in the consolidation request are now accounted for:

1. `feature/dynamic-galaxy-events` — included as the integration base and still being completed.
2. `feature/issue-296-observer-sessions` — already represented exactly in starting `main`.
3. `feature/issue-298-empire-overview` — already represented; integration galaxy wire is a deliberate superset.
4. `perf/salvage-fow-331` — already represented byte-for-byte in the relevant final files.
5. `fix/issue-320-radar-wormhole-search` — already represented; overlapping Scout/validator files are deliberate supersets.
6. `fix/issue-320-remembered-wormhole-rendering` — entirely contained by starting `main`.
7. `fix/issue-319-queued-deployer-package` — already represented byte-for-byte.

Therefore there is **no reason to perform destructive branch-by-branch merges** for six of the seven branches. Doing so would mostly replay historical commits and create conflicts/regressions. The functional integration branch already contains their final behavior plus the new dynamic-event work.

## Consolidation execution order from this point

1. Preserve the current integration branch as the sole target for new work.
2. Close the unnecessary internal #359 audit PR with an explanation; do not delete its source branch.
3. Finish the remaining dynamic-event #297 acceptance work on the integration branch only.
4. Integrate event verification into normal Gradle checks.
5. Run the combined validation wall covering all preserved features.
6. Open/update one draft PR from the integration branch to `main` for final review and CI.
7. Do not merge the integration branch to `main` without a separate explicit decision.

## No-delete / no-regression rule

A merge conflict is not permission to choose one branch wholesale and drop the other. For every conflict or overlapping implementation:

1. identify both behaviors;
2. determine whether one is a newer superset or a truly separate feature;
3. preserve both when compatible;
4. add or retain regression coverage for both;
5. document the resolution here;
6. ask for explicit approval before intentionally removing an already-implemented behavior.

No source branch is deleted as part of this consolidation pass.

## Dynamic event (#297) remaining completeness checklist

The integration base is functional and green, but these acceptance items still require completion or deeper proof before #297 can be called fully done:

- [x] explicit persisted event-entity ownership roles/metadata beyond raw owned-ID sets;
- [x] deterministic completion rewards where applicable;
- [x] exactly-once reward generation/claim behavior across director-state restart and contention;
- [x] event NPC isolation from unrelated normal faction population/economy lifecycle;
- [x] event-specific encounter ordering for distress civilians / attackers;
- [x] safe unstable-wormhole transit draining for ships already touching/committed when collapse begins;
- [x] owner-aware effective topology so strategic/logistics routing can use a discovered temporary shortcut without leaking it to other players;
- [x] route recalculation/blocking when a temporary shortcut closes;
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

### Content-consolidation audit

- Observer source tree vs starting main: IDENTICAL.
- #298 core feature blobs: IDENTICAL; integration `GalaxyMapWire` verified as strategic-summary + event superset.
- FOW performance final blobs: IDENTICAL.
- #320 controls/menu/visibility blobs: IDENTICAL; Scout/validator behavior verified as combined supersets.
- Remembered-wormhole branch: fully behind/contained by main.
- #319 production fix + regression blobs: IDENTICAL.

### Combined branch

The branch has not yet been declared final because new #297 completion work remains. After each event-completion block, record its CI result here. Final acceptance requires the normal Gradle suite plus event, observer, FOW/intel, station-control, production-queue, strategic-summary, galaxy topology, scheduler, save/recovery, multiplayer, and release validation.

#### Local integration validation — rewards / NPC isolation / effective topology / wormhole draining

Validated against the exact PR integration snapshot before pushing the block to GitHub:

- Java 17 full-source compilation: PASS (`javac --release 17`).
- `GalaxyEventValidator`: PASS.
- `GalaxyConnectivityValidator`: PASS.
- `SystemModifierValidator`: PASS.
- `GalaxyMapWireValidator`: PASS.
- `Issue293LogisticsRouteValidator`: PASS.
- `NpcGalaxyDirectorValidator`: PASS.
- `StationControlValidator`: PASS.
- `ProductionQueueValidator`: PASS.
- `git diff --check`: PASS.

This block adds explicit persisted event entity roles, deterministic exactly-once completion rewards, event-NPC isolation and encounter orders, owner-scoped effective topology for temporary shortcuts, route closure behavior, and persisted unstable-wormhole transit draining with bounded safe hard-close behavior. Full `ServerSaveStore` round-trip validation remains separately unchecked below until it exercises these states through the real server save/reload path rather than only event-director capture/restore.

## Conflict / decision log

### 2026-08-27 — source history vs content

A temporary #298-to-integration PR (#359) reported merge conflicts. Content-level inspection showed the relevant #298 implementation was already present, and current `GalaxyMapWire` is a newer superset preserving both #298 strategic summaries and #297 event projection. Decision: **do not force-merge the historical branch and do not overwrite the newer combined file.** This avoids a regression while preserving all prior work.

### 2026-08-27 — FOW performance vs radar wormhole search

Historical branches modify the same Scout/validator files. The current integration versions already contain both behaviors. Decision: retain the integration supersets rather than replacing them with either older branch version.

### 2026-08-27 — production queue branch

The #319 source files and integration files are byte-identical. Decision: no replay merge required; preserve the current integrated files and their validator.

### 2026-08-27 — galaxy-map decode audit

A prior review note suspected that `GalaxyMapWire.decode` might insert `S` system rows twice after the strategic-summary/event integrations. The combined source was inspected before the rewards/topology push: there is exactly one `part.startsWith("S,")` decode branch in the packet loop. Decision: **no code change**; the suspected duplicate was not present, so changing the decoder would have been an unnecessary regression risk.
