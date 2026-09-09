# Issue 383: Procedural galaxy generation

StarChem's procedural galaxy generator composes the existing authored star-system templates. It does not create a second galaxy representation and does not replace the system content library.

## Compatibility

Procedural generation is opt-in. `config/galaxy.json` ships with `generation.enabled` set to `false`, so existing one/two-copy galaxy behavior remains the default until an operator enables the generator.

Existing saves remain concrete snapshots of their systems and runtime state. Loading a save restores those saved systems rather than depending on a newly generated plan.

## Configuration

The `generation` object in `config/galaxy.json` supports:

- `enabled`: use procedural generation when true.
- `seed`: a whole number, arbitrary text seed, or `random`/`auto` to use the runtime world seed.
- `size`: `tiny`, `small`, `medium`, `large`, or `huge`.
- `targetSystemCount`: explicit permanent-system count, bounded from 2 through 64. When present it takes precedence over the size preset count.
- `topology`: `ring`, `clustered`, `hubs`, `frontier`, `dense`, or `mixed`.
- `permanentConnectivityDensity`: 0.0 through 1.0.
- `frontierFrequency`: 0.0 through 1.0.
- `resourceRichness`: 0.25 through 4.0; weights authored resource-rich/high-value templates during composition.
- `rareResourceFrequency`: 0.0 through 1.0; weights authored rare/relic/high-value templates.
- `hazardFrequency`: 0.0 through 1.0; weights authored hazardous/danger-role templates.
- `npcDensity`: 0.0 through 1.0; weights authored faction/NPC-aligned templates such as the Corsair Den.
- `startingSeparation`: graph-distance target used by the FOW-safe start-region planner.
- `templateWeights`: optional per-template positive weights for direct composition control.

The existing `topology.wanderingWormholePairs` setting is still applied after permanent topology generation. Wandering links are represented separately from permanent links so preview/start-region logic can ignore them.

## Determinism

The generator uses separate deterministic seed domains for system composition and permanent topology. Given the same normalized settings, authored template library, and seed, it produces the same generated system IDs and permanent links.

Generated copies use stable IDs: the first template instance keeps the authored template ID, followed by `_2`, `_3`, and so on. `GalaxySystemIdentity` resolves arbitrary numbered generated copies back to their authored template.

## Topology guarantees

Every procedural topology starts from a connected deterministic backbone before optional edges are added. The styles bias the backbone differently:

- `ring`: ring/redundant travel.
- `clustered`: locally connected regions with controlled inter-cluster bridges.
- `hubs`: hub-and-spoke strategic structure.
- `frontier`: sparse core plus protected leaf/dead-end systems.
- `dense`: connected tree with substantially more redundant edges.
- `mixed`: deterministic mixed graph suitable as the general-purpose default.

Permanent generation is capped at 64 systems and 192 permanent links. This intentionally leaves room beneath the multiplayer galaxy-wire limits for dynamically added player-home systems and their links.

## Preview API

`GalaxyPreview.generate(...)` produces a runtime-state-free `GalaxyMapSnapshot` containing only generated systems and permanent links. Ships, bases, resources, controllers, events, and temporary/wandering links are not exposed by the preview model.

The preview also selects deterministic start regions using permanent graph distance and can reject configurations that cannot satisfy the requested starting separation.

A graphical match-setup screen can consume this API without constructing a live `World` or reading fog-of-war/event state.

## Validation

`Issue383GalaxyGenerationValidator` covers:

- same-seed determinism;
- different-seed variation;
- every supported topology style;
- connectivity;
- duplicate/self-link rejection;
- generated-ID/template resolution;
- preview runtime-state isolation;
- start-region selection;
- malformed/oversized settings;
- maximum supported galaxy bounds.

The dedicated GitHub Actions workflow also compiles the project and runs the existing `GalaxyConnectivityValidator` so legacy galaxy behavior remains regression-covered.
