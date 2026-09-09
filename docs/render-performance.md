# Graphics render performance budget

This document defines the initial render-performance contract for the graphics overhaul tracked by issue #408.

## Goals

StarChem should target smooth 60 FPS play at 1080p on the supported desktop baseline. A 60 FPS frame is 16.67 ms, so ordinary gameplay should keep total client render p95 at or below 16.67 ms when practical. The developer F4 overlay remains the source of truth for the complete Swing client frame because it includes `GamePanel`, fog, HUDs, minimap, overlays, and world rendering.

The headless `RenderPerformanceValidator` is the repeatable regression harness for the world renderer. It deliberately uses fixed 1600x900 off-screen rendering, deterministic scene placement, fixed explosion seeds, warm-up frames, and p50/p95/max samples. Shared CI hardware is not stable enough for unconditional absolute millisecond gates, so structural limits always fail closed while absolute timing gates are opt-in for a controlled reference runner.

## Initial p95 world-render budgets

These are the initial controlled-runner limits encoded in `RenderPerformanceValidator`:

| Scene | p95 budget |
| --- | ---: |
| default background/celestial stack | 6.0 ms |
| 20 visible ships | 12.0 ms |
| 50 visible ships | 16.67 ms |
| 100 visible ships | 25.0 ms |
| 100 selected ships | 28.0 ms |
| 8 large stations | 20.0 ms |
| 180 resource nodes | 22.0 ms |
| heavy combat: 80 ships, 220 projectiles, 56 explosions | 33.33 ms |
| capital/station destruction stress | 33.33 ms |
| Nebula Expanse background/celestial stack | 12.0 ms |

All scenarios run at far (`0.45x`), medium (`1.0x`), and close (`1.75x`) zoom. The budgets are intentionally looser for pathological stress scenes than the normal 60 FPS target; those scenes are regression tripwires, not promises that every worst-case frame will hold 60 FPS.

Before making the absolute timing gate mandatory in CI, record the CPU, OS, Java 17 build, display/GPU mode, and JVM flags for the designated reference runner and commit its first CSV as the baseline artifact used for comparisons.

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
- 20, 50, and 100 visible ships;
- 100 selected ships;
- multiple large stations;
- a dense 180-node gas/resource field;
- active combat with ships, projectile shots, and destruction effects;
- capital/station-scale destruction effects at the VFX cap;
- Nebula Expanse with its authored celestial/background configuration;
- far, medium, and close zoom for every scene;
- cold-cache first render and warmed steady-state render samples.

The `incremental` result is the scene median minus the matching empty-system background median. That provides a useful approximation of station, resource-field, fleet, or VFX cost without duplicating production render code in the profiler.

## Running the validator

Windows PowerShell:

```powershell
.\scripts\render-performance.ps1
```

Linux/macOS:

```bash
bash scripts/render-performance.sh
```

Both scripts compile the main source set and write `build/reports/render-performance.csv`.

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

## Interpreting results

Use p95 rather than a single maximum frame as the merge/release signal. JVM warm-up, GC, desktop scheduling, and software-renderer behavior can produce isolated outliers. `max` is still printed for diagnostics.

`cold_ms` includes first-use sprite generation and is useful for detecting expensive procedural content. Warm p50/p95 samples are the steady-state regression metric. The cache hit rate, generation time, entry count, and retained-memory estimate should be checked alongside frame time so a visually correct change cannot hide a cache churn or memory regression.

During normal gameplay, enable the developer F4 performance overlay to correlate validator regressions with complete frame/draw time, world/weapon/fog timings, cache pressure, and active destruction VFX.

## Merge/release rule

Graphics work should be treated as a performance regression when any of the following is true:

1. A hard cache or VFX bound is violated.
2. A controlled-runner scenario exceeds its p95 budget without an explicitly reviewed budget change.
3. A matching baseline scene/zoom regresses by more than the agreed threshold (20% by default).
4. The F4 overlay shows a material full-frame regression that the headless world-render harness does not explain.

If a budget is intentionally changed, update this document and the controlled-runner baseline in the same graphics change so the new cost is explicit rather than silently normalized later.
