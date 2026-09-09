# Graphics render performance budget

This document defines the render-performance contract for the graphics overhaul tracked by issue #408.

## Goals and supported client baseline

StarChem should target smooth 60 FPS play at 1080p on the supported desktop clients. The project currently supports Windows and Linux player launchers and requires Java 17 or newer; StarChem does not publish a minimum CPU/GPU/RAM specification, so this document does not invent one. A controlled reference machine must record its CPU, OS, Java build, display/GPU mode, and JVM flags with any absolute performance baseline.

A 60 FPS frame is 16.67 ms, so ordinary gameplay should keep total client render p95 at or below 16.67 ms when practical. The developer F4 overlay remains the source of truth for the complete Swing client frame because it includes `GamePanel`, fog, HUDs, minimap, overlays, and world rendering.

The headless `RenderPerformanceValidator` is the repeatable regression harness for the production world renderer. It uses fixed 1600x900 off-screen rendering, deterministic scene placement, fixed explosion seeds, warm-up frames, and p50/p95/max samples. Shared CI hardware is not stable enough for unconditional absolute millisecond gates, so structural limits always fail closed while absolute timing gates are opt-in for a controlled reference runner.

## Initial p95 world-render budgets

These are the initial controlled-runner limits encoded in `RenderPerformanceValidator`:

| Scene | p95 budget |
| --- | ---: |
| default background/celestial stack | 6.0 ms |
| 20 visible ships | 12.0 ms |
| 50 visible ships | 16.67 ms |
| 100 visible ships | 25.0 ms |
| 150 visible ships | 30.0 ms |
| 100 selected ships | 28.0 ms |
| 9 large production stations | 20.0 ms |
| 180 mixed asteroid/rock + gas resource nodes | 22.0 ms |
| heavy combat: 80 ships, 220 projectiles, 56 explosions | 33.33 ms |
| capital/station destruction: titans, large stations, projectiles, VFX cap | 33.33 ms |
| Nebula Expanse background/celestial stack | 12.0 ms |

All scenarios run at far (`0.45x`), medium (`1.0x`), and close (`1.75x`) zoom. The budgets are intentionally looser for pathological stress scenes than the normal 60 FPS target; those scenes are regression tripwires, not promises that every worst-case frame will hold 60 FPS.

Before making the absolute timing gate mandatory in CI, record the CPU, OS, Java 17 build, display/GPU mode, and JVM flags for the designated reference runner and commit its first CSV as the baseline artifact used for controlled comparisons.

## Hard resource budgets

The following limits are enforced regardless of machine speed:

- Ship medium-LOD sprite cache: maximum **1,536** retained entries.
- Cache telemetry records requests, hits, misses, generation count/time, evictions, peak entries, and estimated retained pixel memory.
- Destruction VFX: maximum **96 active rendered explosion effects**.
- Destruction VFX: maximum **192 particles per explosion**, or **18,432 particles** across all active explosion effects.
- Effects rejected because the active-effect budget is full become zero-cost suppressed effects and are removed on the next normal effect update.
- The active-effect registry uses weak keys so abandoned worlds or system views cannot permanently consume VFX budget slots.

These limits are render-side only. They do not add simulation or network messages.

## Stress coverage

`RenderPerformanceValidator` covers:

- empty default background/celestial rendering;
- 20, 50, 100, and **150** visible ships;
- 100 selected ships;
- nine large production stations cycling Shipyard, Manufacturing Plant, and Research Lab visuals;
- a dense 180-node field split between `SILICATE_ROCK` and `GAS_CLOUD`, exercising both asteroid/rock and gas rendering paths;
- active combat with ships, projectile shots, and destruction effects;
- a capital/station destruction scene containing Titans, large stations, projectile load, and the active destruction-VFX cap;
- Nebula Expanse with its authored celestial/background configuration;
- far, medium, and close zoom for every scene;
- cold-cache first render and warmed steady-state render samples.

The validator also asserts that the required fixtures retain those properties, so a future refactor cannot accidentally turn the >100-ship, large-station, mixed-resource, or capital-destruction cases into weaker tests while leaving the scenario name unchanged.

## Render-cost attribution

The CSV `incremental_ms` value is the scene median minus the matching empty-system background median. The console report maps those production-path measurements into the issue #408 categories:

- background/celestial stack: absolute empty-system p50;
- Nebula Expanse stack: absolute authored-system p50;
- station rendering: `multi-large-station` incremental p50;
- resource-field rendering: `resource-field` incremental p50;
- combat VFX: `heavy-combat` incremental p50;
- destruction VFX: `capital-station-destruction` incremental p50.

These are isolated production-path attributions, not additive method-level profiler counters. They intentionally call `World.draw` and the existing culling/LOD paths rather than duplicating renderer logic inside the benchmark. The live F4 overlay supplies the complementary full-client frame, world, weapon, fog, selection, cache, and VFX telemetry during actual play.

## Running the validator

Windows PowerShell:

```powershell
.\scripts\render-performance.ps1
```

Linux/macOS:

```bash
bash scripts/render-performance.sh
```

Both scripts use the installed `gradle` command, compile the main source set, and write `build/reports/render-performance.csv`.

To enable the absolute p95 budgets on a controlled performance runner:

```powershell
.\scripts\render-performance.ps1 --enforce-timing
```

To compare a graphics branch against a previously captured CSV baseline and fail if a matching scene/zoom regresses by more than the default 20%:

```powershell
.\scripts\render-performance.ps1 --baseline=path\to\baseline.csv
```

The threshold can be changed for an intentional experiment:

```powershell
.\scripts\render-performance.ps1 --baseline=path\to\baseline.csv --max-regression=10
```

## Pull-request and release regression gate

`.github/workflows/render-performance.yml` runs for renderer/config changes proposed to `main`, for matching pushes to `main`, and on manual dispatch. It always runs the structural validator and archives `build/reports/render-performance.csv`.

For pull requests, the workflow checks out both the proposed head and the PR base. When the base already contains the render harness, it benchmarks the base first on the same GitHub runner and then runs the proposed head with that CSV as its baseline. A matching scene/zoom that exceeds the default **20% p95 regression threshold** fails before merge.

Issue #408 itself is the bootstrap change that introduces the harness, so its base branch cannot run a validator that does not exist there yet. The workflow explicitly reports that condition and still runs all structural/current-branch coverage. After #408 lands, subsequent graphics changes receive same-runner base-vs-head comparison automatically.

For release qualification, use the same validator on the controlled reference runner with `--enforce-timing` and retain the CSV with the release evidence. This separates machine-sensitive absolute budgets from stable PR-relative regression checks.

## Interpreting results

Use p95 rather than a single maximum frame as the merge/release signal. JVM warm-up, GC, desktop scheduling, and software-renderer behavior can produce isolated outliers. `max` is still printed for diagnostics.

`cold_ms` includes first-use sprite generation and is useful for detecting expensive procedural content. Warm p50/p95 samples are the steady-state regression metric. The cache hit rate, generation time, entry count, and retained-memory estimate should be checked alongside frame time so a visually correct change cannot hide a cache churn or memory regression.

During normal gameplay, enable the developer F4 performance overlay to correlate validator regressions with complete frame/draw time, world/weapon/fog timings, cache pressure, and active destruction VFX.

## Merge/release rule

Graphics work should be treated as a performance regression when any of the following is true:

1. A hard cache or VFX bound is violated.
2. A controlled-runner scenario exceeds its p95 budget without an explicitly reviewed budget change.
3. A matching base/baseline scene and zoom regresses by more than the agreed threshold (20% by default).
4. A required stress fixture no longer exercises its documented entity/type coverage.
5. The F4 overlay shows a material full-frame regression that the headless world-render harness does not explain.

If a budget is intentionally changed, update this document and the controlled-runner baseline in the same graphics change so the new cost is explicit rather than silently normalized later.
