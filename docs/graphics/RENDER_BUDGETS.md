# Graphics performance and regression budget

Issue #388 increases visual complexity without changing StarChem's Java2D rendering model. Every graphics child issue must preserve the existing camera, culling and LOD strategy unless profiling demonstrates a better replacement.

## Frame targets

Use these as development gates on a representative desktop rather than promises for every machine:

- 60 FPS target: total frame time <= 16.7 ms.
- World rendering target: <= 10 ms in ordinary gameplay at medium zoom.
- Graphics-overhaul regression gate: a representative scene should not become more than 20% slower than its pre-change baseline without an explicit documented tradeoff.
- No individual static background/celestial layer should perform image decoding or allocate a full-size image every frame.

Record machine/JVM/resolution/zoom when comparing measurements. Compare like-for-like scenes and use medians or percentiles over a sustained sample instead of a single frame.

## Required stress scenes

Profile at far, medium and close zoom where applicable:

1. 20 visible ships.
2. 50 visible ships.
3. 100+ visible ships, selected and unselected variants.
4. Multiple large stations in view.
5. Dense asteroid/mineral/gas region.
6. Active combat with many simultaneous projectiles, impacts, shields and explosions.
7. Capital or station destruction with debris/wreckage.
8. Nebula Expanse with the complete background and celestial stack.

## Cache requirements

- All deterministic expensive imagery should be cached/pre-rendered where practical.
- Every renderer-owned cache must have an explicit upper bound.
- Cache keys must be based on visual identity and only the presentation inputs that actually change pixels.
- Missing/broken assets must be negatively cached so the renderer does not retry JAR/file lookup each frame.
- Cache instrumentation should expose entries, hits/misses and an estimated memory footprint where practical.
- Cache invalidation is permitted for development/reload flows, but normal gameplay should not churn static visual caches.

Current ship medium-LOD cache: 48 heading buckets, 1,536 maximum entries, 144x144 ARGB images. Do not increase those values without profiling the memory/render tradeoff.

## Allocation rules

Avoid in steady-state frame rendering:

- decoding PNG/JPG assets;
- constructing full-screen BufferedImages;
- unbounded collections;
- per-object procedural texture generation;
- per-frame parsing of visual configuration;
- spawning simulation/network entities solely for cosmetic dressing.

Small short-lived Java2D objects are acceptable where existing rendering already uses them, but changes that create measurable GC pressure should move work to cached/precomputed content.

## VFX/debris rules

Particle, debris and wreck systems must define hard maximums before they are merged. When a cap is reached, prefer dropping low-priority/old effects or switching to a cheaper aggregate effect. Destruction visuals must never create an unbounded persistent object list.

## Regression checklist for graphics PRs

- Run normal project compilation/validation.
- Exercise the affected scene at far/medium/close zoom.
- Compare frame/world-draw timing before and after when the change is visually significant.
- Check cache entry count/hit behavior after moving repeatedly through the scene.
- Verify missing/corrupt authored art uses the fallback renderer without repeated I/O or a crash.
- Verify no cosmetic-only data was added to multiplayer authoritative/network state.
- Verify gameplay hitboxes, stats, production, combat and economy are unchanged unless a separate gameplay issue explicitly requires it.
