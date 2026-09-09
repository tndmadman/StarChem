# Nebula Expanse Graphics Vertical Slice

Issue #407 uses Nebula Expanse as the acceptance scene for the #388 graphics overhaul. The scene intentionally exercises the production render path rather than a parallel showcase renderer: the current celestial/background pipeline, gas-resource rendering, authored ship silhouettes/materials, packaged hull art, authored stations, combat/propulsion effects, contextual overlays and ambient environment all remain independently reusable.

## Conversion constraints

- Keep gameplay authority out of visual configuration. System/ship/station visual metadata may select cosmetic treatment, but simulation values continue to come from gameplay definitions.
- Prefer deterministic procedural detail plus small reusable repository assets over large one-off images.
- Keep authored visual bounds separate from collision, range and interaction bounds. Large station architecture may extend past legacy hit radii without changing gameplay.
- Maintain camera LODs. Detailed art is reserved for close views, cached sprites cover medium ship LOD, and far views use cheap readable silhouettes/markers.
- Ambient animation must derive from stable world/simulation time where practical so headless validation remains reproducible.
- Preserve faction readability with restrained ownership accents; structural materials should remain mostly neutral.
- New content must fail safely when optional visual metadata or art assets are missing.

## Acceptance and performance

`Issue407GraphicsBenchmark` renders a representative Nebula Expanse scene containing the Prospector, Frigate, Cruiser, Battleship and Titan plus Outpost, Shipyard and Manufacturing stations and a dense gas field. It checks that the integrated scene produces substantial visible content and reports mean/p95 Java2D render cost next to a simple pre-overhaul-style primitive scene.

The benchmark is a regression signal rather than an absolute frame-time promise because CI hosts vary. Performance budgets that require stable absolute thresholds belong in the dedicated render-performance validator/workflow from issue #408.

Run through the canonical release regression gate, or directly after compiling:

```text
java -Djava.awt.headless=true -cp build/classes/java/main:build/resources/main com.tndmadman.rts.Issue407GraphicsBenchmark
```
