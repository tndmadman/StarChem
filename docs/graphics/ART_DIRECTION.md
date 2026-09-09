# StarChem graphics direction

This document is the visual contract for issue #388 and its child work. The core rule is:

> **Authored identity first; procedural variation second.**

Procedural generation may add wear, decals, small attachments, field density, cloud/noise variation, and other secondary detail. It must not be the primary source of a ship, station, planet, or system's identity.

## Readability hierarchy

1. Gameplay-critical silhouettes and ownership cues.
2. Major environmental forms: star, planets, stations, resource regions.
3. Functional details: engines, hardpoints, docking structures, industrial modules.
4. Surface detail: plating, windows, scratches, decals, dust, cloud/noise layers.
5. Ambient dressing and particles.

If a lower layer makes a higher layer harder to read, reduce or remove the lower layer.

## Material language

StarChem should read as physical machinery in space rather than neon vector art.

- Primary hulls: dark steel, gunmetal, ceramic armor, painted industrial plate, carbon/composite panels.
- Secondary surfaces: exposed structure, radiators, tanks, conduits, antennae, glass, heat shielding.
- Emissives: engines, windows, sensors, reactor/weapon elements. Keep emissive area small relative to the hull.
- Wear: restrained edge wear, soot, heat discoloration, scratches, patched panels. Wear must not erase silhouette readability.
- Avoid large flat fills of saturated faction/player color.

## Ownership and faction color

Player/faction color is an **accent**, not the hull material.

Good uses:
- identification stripes and panels;
- navigation lights or small emissive elements;
- selection/command UI;
- small heraldry/decals.

Avoid:
- painting the entire hull in player color;
- bright player-color glow around every object;
- using ownership color as a substitute for a unique silhouette.

The procedural fallback renderer follows this rule by using a neutral hull with a thin ownership accent.

## Ships

Each ship type needs a recognizable authored hull identity at normal gameplay zoom.

- Silhouette comes first: a player should distinguish roles before reading a label.
- Scouts/light craft: compact, directional, low visual mass.
- Haulers/miners: visible cargo/industrial structure and asymmetric functional detail are encouraged.
- Combat ships: readable weapon/armor mass and clear forward direction.
- Capitals: large negative spaces, repeated structural modules, secondary hull forms, and scale cues.
- Procedural variation may alter decals, wear, windows, minor panels, antennae, or small secondary modules; it must not substantially change the primary outline.

Authored ship images are conventionally loaded from `src/main/resources/art/ships/<safe-ship-type-id>.png`. They face +X in local renderer space and are centered on the image. Missing art must render through the deterministic fallback rather than fail.

## Stations

Stations must look constructed for a purpose and significantly larger/more complex than ships.

- Use repeated modules, trusses, tanks, docking elements, hangars, radiators, windows and service structures for scale.
- Industrial, research, military and logistics stations should not share the same primary architecture with only a color change.
- Ambient animation should be slow and bounded: navigation lights, docking activity, rotating modules where appropriate.
- Detailed status belongs in contextual UI, not permanent world-space text stacks.

## Celestial bodies

- Stars: layered disk/corona, restrained activity and local lighting influence.
- Planets: authored palette/material identity plus surface, atmosphere, cloud and shadow layers as appropriate.
- Moons: distinct surface treatment and lower atmospheric emphasis unless specifically authored otherwise.
- Never reduce a major body to a single flat filled circle at normal gameplay zoom.

## Space environments

System backgrounds should create place identity while preserving gameplay contrast.

- Use several low-frequency layers: sparse large stars, dense dim starfield, dust, nebula/cloud structure and optional system-specific phenomena.
- Keep the center playfield quieter than decorative outer regions when possible.
- Avoid high-contrast stars directly behind ships/stations.
- Parallax must remain subtle enough that it does not look like the world layer is sliding independently of the camera.
- The prototype debug/grid treatment is not part of the final visual language.

## Resources

Asteroids, mineral deposits and gas should read as **regions**, not isolated UI markers.

- Preserve the simulation node as the gameplay authority.
- Render clustered supporting rocks/dust/debris or gas wisps around it deterministically.
- Material color can inform the field, but should not turn the entire region into a saturated blob.
- Resource dressing must remain culled/LOD-aware and must not create simulation entities.

## Combat and effects

- Effects must communicate source, direction and impact before spectacle.
- Weapon fire should originate from authored hardpoints when available.
- Engine effects should be anchored to engine positions and scale with ship class/thrust state where practical.
- Shields should be event-driven and localized where possible instead of permanent bright bubbles.
- Explosions should use staged light/debris/smoke or plasma elements with explicit particle/debris caps.
- Persistent wreckage must have an upper bound and should use cheap distant LOD.

## Lighting and glow

- Treat stars and emissive hardware as light sources; keep most surfaces materially dark.
- Glow is an accent. Do not use it as an outline around every object.
- Bloom-like halos should be soft, low-opacity and spatially limited.
- A scene with effects disabled should still have readable objects and material depth.

## Zoom / LOD targets

- Far: icon/silhouette and ownership recognition only. Do not draw surface detail.
- Medium: cached authored silhouette/material blockout with a few high-value accents.
- Close: authored surface detail, hardpoints, paneling, windows, engines and controlled variation.

Existing camera culling and ship LOD thresholds are part of the performance contract unless profiling proves a replacement is better.

## Do / don't

**Do:** neutral physical materials, distinctive silhouettes, localized accent color, restrained emissives, reusable authored assets, deterministic secondary variation, cached expensive imagery.

**Don't:** full-hull team-color fills, random polygons defining major objects, dense permanent labels, per-frame texture/image generation, unbounded particles/debris, excessive glow, or detail that destroys tactical readability.
