# StarChem PR #457 — implementation, verification and merge plan

**Repository:** `tndmadman/StarChem`  
**Existing work branch:** `integration/four-branch-20261105`  
**Existing integration PR:** [#457](https://github.com/tndmadman/StarChem/pull/457) → `main`  
**State:** DRAFT. Do not tag, publish, merge or undraft while any gate remains red or unverified.

## Purpose and invariants

Finish, rather than re-create, the existing integration of #453 (celestial/extraction/lifecycle), #454 (cleanup and performance), #455 (solo saves), and #456 (Guided Start/economy). Preserve protocol 17, rules 27, archive format 6, production recipes and identifiers, Windows `.bat` and Linux launchers, multiplayer auth and fog authority, and all existing build.gradle validators. Do not remove failing scenarios, soften any threshold or conceal test failures.

**User-confirmed Windows problems that motivated the fixes:** Load with zero saves opened a detached window; Save and Save As opened Swing popups outside the game; Load/Recovery/Rename/Delete and Quit/Return confirmation used out-of-frame dialogs; the station-control popup remained above full-frame Manufacturing. The earlier checkbox states are not verifiable.

## What is already implemented

The integration branch already includes:
- `CampaignOverlayController` and wired in-frame Save, Save As, Load, Recovery, Rename, Delete, Exit/Quit/window-close, messages, and a status-only empty-slot path.
- Station menu dismissal via `GameFrame` for station Open Manufacturing and F9.
- Fresh 15-Prospector + owned Tier-1 radar startup, configurable 1–50 in solo/guided lobby and `--starting-miners` for dedicated server, persisted starting policy with old-save/reconnect protection.
- Guided first Iron-delivery objective replacing obsolete buy-a-second-miner step; updated tutorial migration and catalog/economic checks.
- Exact Continue snapshot selection, per-slot save restoration, scenario director runtime, audio candidate suppression, cleanup, and CI validators.
- Continuation commits: the render-pass snapshot optimization, deferred procedural generation policy commit, 10 rejected candidate rollback tests, and queued manufacturing reservation/recovery test.

This is code-complete for the originally reported UI/startup bugfixes **subject to hands-on verification**. No source-level claim is a substitute for manual Windows testing.

## Release blocker P0 — renderer

1. Reproduce both gates *on the exact new head*:
   - `.github/workflows/render-performance.yml`: unchanged 33 scene/zoom keys, 5 alternating paired baseline/head measurements, <=20% median p95 regression.
   - `.github/workflows/graphics-integration-validation.yml`: frozen-baseline headless scene comparator and all graphics validators.
2. Keep the raw CI artifacts and identify repeatable hot-path differences. Earlier PR-head Render Performance run 42165093456: **background-default at 0.45x p95 +25.7%, 32/33 passed**. Earlier integration graphics run 42165087387: multiple single-run p50 fleet-scene differences. Single-run fluctuations must not be misreported as verified regressions or verification passes.
3. Existing direct optimization: `WorldRenderCandidates.prepareFrame` checks the spatial index **once** and reuses frame visibility for bases, resources, items and units. Compare against unchanged baseline to establish whether it actually resolves the failures.
4. If still failing, profile timed segments of `World.draw` and `GalaxyCoordinator.draw/ drawMap`, culling, selection/fleet overlays, sprite caching, Software Java2D raster/GC. Optimize the production path without changing pixels, dropping stress, or diluting 20%/33-scene contracts.
5. Repeat original five-pair workflow until it reports 33/33 PASS on the final commit. Keep PR draft if failure remains. Record absolute F4 full-frame measurements on actual Windows at 900x720, fullscreen and dense fleets; headless timing is not a substitute.

## Persistence and failure isolation P1

1. Candidate archive must validate and construct without replacing current visible `World`. `World` now stages candidate `GalaxyRuntimeOptions.State`; `ServerSaveStore.readWorld` restores global options/worker PlayerRegistry in `finally` even when valid candidate load succeeds; `GameFrame` commits staged options only after stopping old session.
2. Test 10+ forced failures **after candidate World creation**: registry, audio listener, generation settings, fog and current world must remain intact; any abandoned candidate must dispose registrations and caches.
3. Exercise A→B→A→B multiple distinct seeds, fog and slot IDs, corrupt C, backup fallback, exact manual/previous/latest snapshot selection, and 10+ real game-frame swaps. Archive format must stay 6 and integrity checks unchanged.
4. Validate old queued production, reservations, recipe IDs and research migrations. `SoloSaveLoadValidator` now saves a paid unfinished `research_matrix` job, restores identical reservation/remaining time and completes production afterward; also reads the old seven-column job encoding. Extend with historical sample saves when available.
5. Preserve dedicated server authentication sessions and stable TLS identity separately from solo. No accidental solo credential serialization.

## Guided Start to first Hauler P1

1. Spawn a fresh campaign with exactly 15 mining Prospectors, one Outpost and one owned `radar_picket`. Confirm optional count overrides and already-existing legacy saves remain unchanged.
2. Actually select miners, mine and unload first 5 Iron into owned hangar, ensure objective does not auto-complete before deposit, and verify objective after Save/Continue.
3. Earn all manufacturing inputs using real resource nodes and ship/station commands. Queue Deployer, construct Manufacturing Plant, queue intermediate components, create Laboratory and supply Fuel, complete Advanced Industry → Logistics Engineering → Autonomous Logistics through the research queue, build/deploy Shipyard, queue and complete first Hauler. No direct `stockpile.put`, test-only dev-free-build, direct research completion, or synthetic objective event as proof.
4. Automated headless integration simulator should drive actual game commands and record timings, produced jobs, research and objective transitions; also perform Windows manual playthrough. Current static reachability and budget validators are **not** proof of complete playthrough.
5. Update tutorial checkpoint tests, economy budget, in-game hints and docs from observed real route. Restore multiple checkpoints and ensure no ghost duplicated assets.

## UI and Windows acceptance P1

1. Source and packaged `.bat` launchers at 900x720 and fullscreen. Load zero slots only updates lobby status. With slots, every save/load/recovery/rename/delete/confirmation remains inside the game frame, with Esc, focus, tab traversal, blocking overlays, correct failure retries and no simulation click-through.
2. Save As must never replace active slot before durable successful save; failing write removes failed candidate slot. Cancel must restore timer/focus state. Window close, Quit, Return each offer Save & Exit / Exit Without Saving / Cancel in frame.
3. Click Manufacturing station and Open Manufacturing / Manage Policies: no station popup above it. F9, keyboard shortcuts and overlay priority correct with minimap/HUD/tutorial.
4. Run repeated rapid buttons, frame resize/fullscreen changes, lost disk permissions, missing/corrupt archives, and audio world swaps.
5. Report **PASS / FAIL / NOT RUN** per item; do not infer user checkmarks from a screenshot or claim Windows testing based solely on Linux CI.

## Dedicated multiplayer acceptance P1

1. Start dedicated server with `--starting-miners N` including 1, 15 and 50. New commander receives configured count and one T1 radar; existing commander reconnects with **no extra gifts**. Restart server and add a different commander; saved policy still applies.
2. Two authenticated clients: simultaneous state, authority, fog isolation, observers, TLS identity preservation, reconnection, campaign save/restart. Confirm protocol 17 and rules 27 compatibility and no solo archive exposure.
3. Distinguish CI/headless simulation from two real independent clients. Missing credentials/second Windows machine are blockers, not evidence of success.

## Automated test and final-head evidence

- Java 17 / Gradle 8.10.2: `gradle clean classes check jar --no-daemon`.
- `validateSoloSaveLoad`, `validateWorldLifecycle`, `validateGalaxyInactiveSchedulerRoster`, `benchmarkSoloSaveLoad`, `benchmarkGalaxySchedulerPreparation`.
- `bash validation/run-release-regressions.sh 'build/classes/java/main:build/resources/main'`.
- `bash validation/run-v180-rc-acceptance.sh 'build/classes/java/main:build/resources/main'`.
- All GitHub Actions workflows on the **same final head**, including Java CI, feature rollup, scenarios, research, Swing UI, security, observer, celestial, graphics integration and unchanged paired render performance.
- Launchers and full manual acceptance for Windows, dedicated multiplayer and genuine first Hauler are separate gates; never label them PASS unless executed with evidence.

## Decision rule

**Merge only when** all code/test gates are green **and** mandatory manual acceptance is documented, with explicit user authorization to merge. Otherwise leave #457 DRAFT on the same branch, log exact failures, provide minimal repro instructions, and state what prevents completion. Never delete integrated features to force green.

## Full copy/paste takeover/continuation prompt

> You are the StarChem lead integration and verification engineer. Continue existing draft PR #457 in repository `tndmadman/StarChem`, branch `integration/four-branch-20261105`; refresh live SHA and CI first. PR #457 already integrates #453/#454/#455/#456 and a follow-up in-frame campaign overlay, popup cleanup, configurable 15 miners/T1 radar, Guided objective migration, candidate load isolation and render visibility optimization. Do not restart from main or silently change protocol 17, rules 27, save format 6, launcher files, recipes or authority/security. Read this entire plan, original PR body, and code before making changes.
>
> Your task is to **implement, test, repair, commit and prove acceptance**, not to deliver another planning-only reply. Prioritize passing the unchanged 5-pair/33-case/20%-p95 render regression; investigate raw base/head CSVs and real hot paths, then fix without weakening the gate. Extend automatic true command-driven first-Hauler gameplay; prove old queued jobs/reservations and scenario checkpoints survive save and reload; perform ten or more corrupted candidate restores and A/B swaps with global audio/registry/generation/fog isolation. Verify in-game Save/Load/Save As/Recovery/Delete/Return/Quit dialogs, station→Manufacturing popup dismissal, customizable startup for solo and dedicated server, no gifts on reconnect, existing multiplayer TLS/auth/fog/observer behavior. Preserve all integrated features.
>
> Use Java 17 / Gradle 8.10.2. Run every relevant Gradle validator and full current-head GitHub Actions matrix. Do not claim real Windows, actual two-client or legitimate first Hauler testing unless truly performed. On each code change, commit directly to existing integration branch, refresh final SHA, attach CI links and update PR #457 with exact passes and failures. Keep PR DRAFT, main unchanged, no release/tag or auto-merge if *any* required gate is failing, pending or unverified. Your final report must name all changed files, commits, validations and remaining blockers and provide exact manual retest steps.
