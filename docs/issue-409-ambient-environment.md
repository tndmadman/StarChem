# Issue #409 — Ambient system life and foreground space dressing

This document records the implementation and manual QA expectations for issue #409.

## Implementation contract

Ambient visuals are presentation-only. `AmbientSystemRenderer` owns deterministic render data for each star-system definition and never adds entities to `World`, collision, targeting, selection, save state, or network snapshots.

The renderer currently supports distinct treatments for:

- Deep space — sparse dust and occasional meteor streaks.
- Ice systems — cool dust and drifting ice fragments.
- Warzone systems — muted debris, ember fragments, wreck silhouettes, and occasional streaks.
- Nebula/gas systems — slow wisps, gas motes, and colored dust.
- Ancient graveyards — denser wreck silhouettes and cold drifting debris.
- Forge/volcanic systems — warm haze and rising ember-like motes.
- Pulsar systems — cool dust and restrained rotating stellar shafts.

Two parallax depths are used so ambient layers move more slowly than normal world-space gameplay objects. Density falls at far zoom. Generated fields are cached by system definition and hard-bounded to 280 decorative entries per system; individual effects are view-culled before drawing.

## Automated validation

Run:

```bash
gradle -I gradle/issue409-ambient-validation.gradle validateIssue409AmbientEnvironment
```

The validator checks:

- at least three distinct system treatments exist;
- expected theme routing for Ice Belt, Warzone, Nebula Expanse, and Ancient Graveyard;
- deterministic and bounded ambient field metadata;
- far/mid/near zoom density scaling;
- far-layer parallax moves less than mid-layer and both move less than gameplay world space;
- identical system/time/camera inputs produce identical rendered pixels;
- multiple representative themes visibly animate over time;
- representative ambient stacks render successfully in headless Java2D.

The dedicated GitHub Actions workflow runs the validator whenever the ambient renderer, celestial integration, system configs, validator, or its Gradle wiring changes.

## Manual gameplay QA

Check the following at normal gameplay zoom and at the far zoom limit:

1. **Ice Belt** — ice fragments should read as small environmental glints, not mineable rocks or ships.
2. **Warzone** — wreck/debris silhouettes should add depth but remain visibly subdued behind combat units and projectiles.
3. **Nebula Expanse** — wisps should drift slowly and never obscure a target, selection, resource, or projectile for more than a moment.
4. **Ancient Graveyard** — wreck density should be visibly higher than Warzone while remaining background dressing.
5. **Volcanic Crucible / Binary Forge** — warm haze and motes should reinforce the hot-system identity without washing out UI or unit colors.
6. **Pulsar Reach** — rotating stellar shafts should remain subtle and should not look like weapon beams or targeting lines.

Pan the camera through each scene and confirm the far layer moves less than the mid layer, with no sudden jumps or screen-locked particles. Zoom fully out and confirm density visibly drops.

Decorative objects must not respond to click, drag-selection, targeting, collision, harvesting, or damage. Gameplay ships, resources, stations, projectiles, selection markers, and HUD information must remain visually dominant.

## Performance boundary

Issue #409 deliberately avoids simulation/network work. Render data is generated once per cached system field, particle/debris counts are explicitly bounded, and off-screen decorations are culled. Broader before/after frame-budget benchmarking belongs to graphics performance issue #408; #409 must continue to satisfy that shared budget as the rest of the visual overhaul lands.
