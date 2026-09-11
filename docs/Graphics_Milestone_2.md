# Graphics Milestone 2

Graphics Milestone 2 is the coordinated StarChem graphics overhaul represented by issues #385, #388-#394, #396, #398-#399, #401, #403, #405, and #407-#409.

The implementation was reconciled through `integration/2026-09-09-graphics`, merged into the combined feature/graphics/security integration through #439, and receives its final acceptance-gap validation in #440.

## Production architecture

The milestone keeps one production ownership model rather than parallel showcase renderers:

- `SpaceBackgroundRenderer` owns deterministic system backgrounds and parallax.
- The celestial renderer owns stars, planets, moons, rings, atmospheres, and surface treatment.
- `ShipVisualCatalog` and the production ship renderer own authored hull silhouettes and hardpoints; material, panel, and faction accents layer on that identity.
- `StationRenderer` owns authored station architecture while `StationPresentation` owns contextual overlays.
- Production resource rendering owns asteroid, mineral, and gas environments.
- Combat and destruction VFX use bounded production effect lifecycles.
- Ambient environmental motion remains deterministic, bounded, and cosmetic.

The integration rule remains: **authored identity first, procedural variation second**.

## Nebula Expanse acceptance

The Nebula Expanse vertical slice is not a synthetic showcase path. It exercises the production background, celestial, station, ship, resource, VFX, destruction, and ambient rendering systems.

`Issue407GraphicsBenchmark` validates the production `SpaceBackgroundRenderer` across every predefined system at explicit dimensions. It verifies deterministic system seeds, performs warmup and measured samples, reports p50/p95, and enforces timing thresholds with system/seed diagnostics.

## Performance and regression coverage

Graphics acceptance includes:

- the production render-performance suite and representative scene/zoom fixtures;
- bounded sprite/effect cache and lifecycle checks;
- visual-content and system-specific rendering validators;
- issue #407 production background benchmarking;
- canonical release rendering/regression validation.

The PR-level performance workflow compares a same-runner baseline and proposed head. Hosted-runner tail latency may vary, so diagnostic p95/max values must be interpreted alongside the deterministic structural budgets and the final integrated acceptance suite.

## Integration record

Detailed branch provenance, subsystem ownership, contamination removal, and the original graphics-rollup validation are documented in `docs/integration/2026-09-09-graphics.md`.

Final milestone acceptance is tracked by PR #440. The milestone is considered complete only after #440 is merged and its exact resulting `main` SHA passes the final integrated acceptance workflow.
