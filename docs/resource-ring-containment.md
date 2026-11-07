# Resource ring containment and regeneration

Natural resource nodes in authored system templates are permanently associated with a stable belt ID.
The belt JSON fields `orbit`, `width`, and `arc` define a bounded radial annulus and an
initial partial arc, not an unlimited Gaussian cloud.

- A resource's orbital radius must lie within `[orbit - width, orbit + width]`.
- A new node's angle is selected within the configured `arc` around the belt's center.
- A belt's center orbits at its reference rate, and new nodes share that rate so the
  initial partial-arc cluster stays coherent instead of spreading into a complete circle.
- Regeneration after depletion happens inside the same belt, even after all nodes of a
  material have been mined. It preserves the resource's material, node ID, capacity and kind.
- Respawns attempt to avoid stations and active resource nodes, but if crowded they
  always prefer a valid ring position over unrestricted scattering.
- Planetary fracture deposits and scripted events are not coerced into natural belts.

New saves persist `resourceBeltId` and `resourceBeltCenterAngle`. Loading older saves
infers origins only from the generated `Belt Name MATERIAL index` node pattern, never
from material alone, and clamps out-of-range orbital radii back into the source ring
without changing current inventory or depletion timers. Active resources inside a ring
are not teleported merely to reconstruct their legacy angular center.

Run `./gradlew validateResourceRingPlacement` to check every configured system belt
over multiple seeds, repeated complete aluminum depletion, authoritative respawn,
and new/legacy save round-trips. The validator is also included in `check` and
the canonical release regression script.
