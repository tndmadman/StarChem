# StarChem PR #457 — completion plan and continuation agent prompt

Updated: 2026-11-06. Source of truth: live PR #457 and GitHub Actions, not this document's last-observed numbers.

## Repository and non-negotiable constraints

- Repository: `tndmadman/StarChem`.
- Existing **draft** PR: https://github.com/tndmadman/StarChem/pull/457
- Branch: `integration/four-branch-20261105`, base: `main`.
- PR integrates original #453/#454/#455/#456. Preserve every feature inherited from those branches. Do not recreate a parallel branch or new PR.
- Do not change published protocol 17, rules 27, archive format 6 or launch scripts absent verified compatibility need and explicit review.
- Do not disable scenes, lower sample counts, alter fixtures, relax the **20% median p95 render regression gate**, or claim benchmark success based on unrelated passing workflows.
- No force-push, auto-merge, undrafting, tagging or releasing before every mandatory acceptance criterion below passes **on one identical final head SHA**.
- Treat UI, save, and network state as production data. Ensure archive failures preserve current session, never overwrite a valid checkpoint, and never duplicate player assets.

## Feature inventory already implemented on this branch

1. Integrated deterministic celestial systems, scheduler, bridge teardown, lifecycle cleanup, scenario and resource mechanics.
2. Solo UUID save slots, autosaves and exact recovery file selection; checksum-validated atomic archives; per-campaign fog, scenario, tutorial, camera, production queues and seed; old archive compatibility.
3. In-game `CampaignOverlayController`: Save, Save As, Load, Rename, Delete, recovery, Return and Quit confirmations, status when no saves, asynchronous busy state and errors. Manufacturing station popup dismissal/F9.
4. Starter policy: **15 total Prospectors and one T1 radar** for new solo, Guided and first-time multiplayer commanders; lobby spinner 1–50, server `--starting-miners`; per-world persistence and no reconnect gifts. Historical archives use legacy one-Prospector policy.
5. Guided Start first objective now genuinely requires 5 Iron stored at an owned base, not a free 2nd ship. Tutorial schema v4 migration. Headless mining extraction/delivery scenario validator.
6. Candidate load registry/audio/generation state isolation, plus ten repeated injected post-construction restoration failure checks in SoloSaveLoadValidator.
7. On 2026-11-06 this continuation added viewport-only grid-stroke drawing in `GalaxyCoordinator.drawMap`; pixel equality validation in `RenderPerformanceValidator.validateBackgroundGridPixels`; and an in-frame **Save & Load / Load Without Saving / Cancel** choice in `GameFrame.loadSolo`.

## Current CI observation and performance blocker

At SHA `2183635af8a368252370e0c87773faaa683b5440`, Java CI, Game Swing UI, Feature Rollup, Scenario, Security, CI, release-candidate and most workflows passed. Required **Render Performance** failed: one of 33 cases, `background-default` zoom `0.45`, five-run median p95 baseline 11.390ms vs candidate 14.316ms (+25.7%, cap +20%). Graphics Integration also failed its sustained render comparison. Earlier runs showed +36.1% and +76.5% in this same scene. The baseline and candidate both draw a 160px full-world grid and celestial scene. A culling optimization now exists but its effect has **not yet been proven** by an exact-head five-pair pass. Once new CI completes, check logs and compare all 33 rows, not just the headline.
- Render Performance prior failing run: https://github.com/tndmadman/StarChem/actions/runs/42165093456
- Graphics Integration prior failing run: https://github.com/tndmadman/StarChem/actions/runs/42165087387

## Implementation phases, in strict priority order

### Phase 0 — Refresh GitHub state

1. Read PR #457 metadata and exact head SHA; verify branch still targets `main` and remains draft.
2. Retrieve file diff and relevant workflow logs for the current SHA, especially `Render Performance`, `Graphics Integration Validation`, `Java CI`, `Game Swing UI Validation`, `Scenario Validation`, `Feature Rollup Validation`.
3. Identify failed *step* and log excerpts. Discriminate deterministic visual failures from benchmark scheduling/GC noise.
4. Check whether any earlier claimed validation is only from an older SHA. Do not copy prior green results onto new code.

### Phase 1 — Fix all blocking render results with real optimizations

1. Run/inspect unchanged 11 scenes × 3 zooms and five alternating baseline/head pairs. Verify `validateBackgroundGridPixels` passes; if not, repair conservative anti-aliased clip-edge handling while retaining pixel identity.
2. Profile `background-default` at 0.45× using production paths `World.draw`, `GalaxyCoordinator.drawMap/draw`, `CelestialSystem.draw`, `WorldRenderCandidates.prepareFrame`, `WorldSpatialIndex`, and tracing/GC allocation. Preserve exact visual output and gameplay logic.
3. Optimize verified costs, not the benchmark. Potential optimizations: eliminate offscreen grid strokes, avoid repeated heavy celestial shape or text computations, reuse immutable paint state where safe, reduce redundant raster work, and remove avoidable per-frame allocations. Do not create cross-world stale caches.
4. Re-run comparator unchanged until 33/33 scenarios pass under <=20% median-p95 threshold. Graphics Integration sustained gate must pass as well.
5. Retain pixel equivalence tests and independently document all graphics changes.

### Phase 2 — Guided Start to actual first Hauler

1. Build deterministic headless validator that follows real player permissions, resource inventory, research unlocks, recipes, manufacturing, deployer, laboratory, shipyard and unit production. Do **not** inject units/resources or call developer free-build.
2. Assert 15 starting Prospectors and T1 radar at start but zero free research/progression completion. Move a Prospector to mine and unload at least 5 real Iron to activate first objective.
3. Complete prerequisite tree using actual crafting queues and world ticks; verify reachability within a bounded, sane timeout and resource availability. Validate first authentic Hauler enters the world.
4. Save/reload several checkpoints; assert objective state, acquired technology, paid reservations, fleet count, fog, tutorial state and no repeated scenario rewards or duplication.
5. Keep scenario IDs and tutorial migration stable; adjust tutorial steps if any are unplayable after 15-miner startup. Write trace output indicating every completed objective and elapsed simulated time.

### Phase 3 — Save/archive/GUI hardening

1. Verify exact snapshot selection, no unexpected detached `JOptionPane` on all user-visible solo flows; modal layering and focus/ESC semantics in 900×720 and fullscreen.
2. Exercise full Save, Save As, Load, Save & Load, recovery, rename, delete, autosave, Return, window-close and Quit choices. Save failure must never trigger load/exit and must never discard the live world. Load failure must leave world, audio, registry, options, timer and focus usable.
3. Exercise corrupt current+manual/previous fallback, truncated archive, checksum mismatch, duplicate system IDs, malicious names/paths, 10+ repeated failed restore cycles, and repeated cross-slot loads.
4. Validate old format-6 archives lacking `startingFleetSettings`, old tutorial schemas, existing 7-column production queue rows, paid resource reservations and partially complete research/Hauler production. Compare before/after inventories, queue time and outputs for no duplication/loss.
5. Confirm no modal overlay swallows save-completion callbacks or lets game inputs leak through.

### Phase 4 — Dedicated multiplayer

1. Start a dedicated server with `--starting-miners 15` and a separate test with non-default 30; two authenticated clients join in separate identities.
2. Verify each new commander gets exactly N Prospectors, one Outpost and one T1 radar in valid space, no overlap/unauthorized shared ownership. Test rejoin, reconnect, disconnect and observer mode without asset grants.
3. Save/shutdown/restart dedicated server with a changed CLI count; validate existing commanders unchanged and **saved** policy applies to later new commanders. Verify fog isolation, networking, command authority, spectator visibility, scenario authority and no state leaks.
4. Test Windows `.bat` and Linux launchers. Ensure developer commands and solo-only panels remain inaccessible to network clients.

### Phase 5 — Windows manual QA and final release

1. Real Windows 900×720 plus fullscreen: lobby, Guided, Custom, Continue, Load, slots, keyboard Enter/Escape, Settings, Save & Exit, Return/Quit, F9/Manufacturing station, popup z-order.
2. Verify two GPU/render regimes and production audio after save restoration. Capture F4 p95 and inspect real startup, mining, manufacturing and fleet draw.
3. Final command coverage: Java 17 & 21, `gradle clean classes check jar`, `validateSoloSaveLoad`, full CI matrix, graphics sustained and unchanged 33-scene five-pair 20% benchmark.
4. Attach a completion checklist with exact SHA, run URLs, tested OS, commands and outcomes to PR #457. Merge only after **all mandatory checks pass** and manual/hardware tests are explicitly attested. Otherwise keep draft with truthful blockers.

## Exact final acceptance checklist

- [ ] CI: Java 17 + 21, clean build, classes/check/jar and security/integration validators green on one SHA.
- [ ] UI: no external Swing popups for campaign flows; station menu always dismisses; keyboard/mouse focus and 900×720/fullscreen manually verified.
- [ ] Startup: solo, Guided, dedicated and reconnect tested at default 15 and overridden N; one radar; old archives preserved; settings persisted.
- [ ] Guided: complete authentic playable path through actual first Hauler and checkpoint replay.
- [ ] Saves: exact Continue/recovery choice, CRC/checksum and atomic writes, corrupted/truncated fallback, repeated failure rollback, older production/research/tutor compatibility.
- [ ] Dedicated: two authenticated clients, observer/fog isolation, restart/rejoin, ownership/authority, no duplication.
- [ ] Performance: 33/33 unchanged production scenarios and separate sustained comparator <=20% against main on current SHA; no visual pixel divergence.
- [ ] No launcher, protocol, archive format or unrelated game regression.
- [ ] PR review evidence recorded; branch only, then authorized merge to main.

## Agent prompt — copy into a new coding session

> You are the continuation implementation agent for **tndmadman/StarChem**. Your mission is to complete **existing draft PR #457** in place on **integration/four-branch-20261105** and merge to `main` **only when every mandatory gate in `docs/pr457-completion-plan.md` has demonstrably passed**. Do not create a replacement PR or new branch; do not skip the inherited #453–#456 functionality. First read this whole document, PR #457 body/diff and original handoff, inspect the exact current head and workflow logs, and make a detailed checklist. The last known blockers were background-default 0.45× render performance (+25.7% over main; max allowed +20%) and Graphics Integration sustained render regression. A viewport grid stroke culling optimization and pixel equality validator were committed on Nov 6 but are not verified; inspect fresh results. Keep all 33 scenes, 5 base/head pairs, and fixed 20% threshold. Profile and fix renderer production code if needed. Then implement a true physics/economy/queue/research Guided Start to first-Hauler replay test without cheating materials or ships; harden old-save queue migration, campaign rollback and in-frame modal behavior, and implement/take evidence for a two-client authenticated server restart/reconnect test. Run Java 17/21, CI, 900×720/fullscreen Windows and performance/manual gates. Preserve protocol 17, rules 27, save format 6 and launchers. Keep the PR draft until all checks pass, update the PR with actual current-head results and run URLs. If access lacks Windows GUI/two clients, explicitly mark those gates unverified instead of guessing or merging. Continue implementing and testing in this session; do not stop after only writing a plan. In your response, distinguish implemented code, verified automated tests, failed tests, and inaccessible manual gates. No claims that work was finished unless the same exact head passed every gate.
