# Issue 383: Procedural galaxy generation

StarChem's procedural galaxy generator composes the existing authored star-system templates. It does not create a second galaxy representation and does not replace the system content library.

## Compatibility

Procedural generation is opt-in. `config/galaxy.json` ships with `generation.enabled` set to `false`, so existing one/two-copy galaxy behavior remains the default until an operator enables the generator or a Solo player selects Procedural galaxy in match setup.

Existing saves remain concrete snapshots of their systems and runtime state. Loading a save restores those saved systems and wormhole links rather than depending on a newly generated plan.

## Match setup

The graphical Solo lobby exposes:

- Procedural galaxy on/off, preserving the existing one/two-copy mode when disabled.
- Tiny / Small / Medium / Large / Huge size presets.
- Ring / Clustered / Hubs / Frontier / Dense / Mixed topology styles.
- Numeric, text, or random galaxy seeds.
- Advanced controls for permanent connectivity, frontier/dead-end bias, resource richness, rare-resource frequency, hazards, NPC/faction density, and starting separation.
- A graphical permanent-topology preview.

When `random` is selected, the lobby resolves it to a concrete numeric seed before preview or launch. This makes the displayed preview reproducible and guarantees that launching immediately afterward uses the same galaxy.

The same generation defaults can still be configured by operators in `config/galaxy.json`; match-setup choices are world-scoped runtime settings and do not rewrite the configuration file.

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
- `startingSeparation`: graph-distance target used by the FOW-safe start-region planner and live player-home placement.
- `templateWeights`: optional per-template positive weights for direct composition control.

The existing `topology.wanderingWormholePairs` setting is still applied after permanent topology generation. Wandering links are represented separately from permanent links so preview/start-region logic can ignore them.

## Determinism

The generator uses separate deterministic seed domains for system composition and permanent topology. Given the same normalized settings, authored template library, and seed, it produces the same generated system IDs and permanent links.

Generated copies use stable IDs: the first template instance keeps the authored template ID, followed by `_2`, `_3`, and so on. `GalaxySystemIdentity` resolves arbitrary numbered generated copies back to their authored template and gives numbered copies distinct display names.

## Topology guarantees

Every procedural topology starts from a connected deterministic backbone before optional edges are added. The styles bias the backbone differently:

- `ring`: ring/redundant travel.
- `clustered`: locally connected regions with controlled inter-cluster bridges.
- `hubs`: hub-and-spoke strategic structure.
- `frontier`: sparse core plus protected leaf/dead-end systems.
- `dense`: connected tree with substantially more redundant edges.
- `mixed`: deterministic mixed graph suitable as the general-purpose default.

Permanent generation is capped at 64 systems and 192 permanent links. This intentionally leaves room beneath the multiplayer galaxy-wire limits for dynamically added player-home systems and their links.

## Preview and start regions

`GalaxyPreview.generate(...)` produces a runtime-state-free `GalaxyMapSnapshot` containing only generated systems and permanent links. Ships, bases, resources, controllers, events, and temporary/wandering links are not exposed by the preview model.

`GalaxyPreviewPanel` renders that model directly in the Solo setup UI. The preview selects deterministic candidate start regions using permanent graph distance and can reject a requested preview when the requested number of starts cannot satisfy the configured separation.

Live procedural player-home creation uses the same deterministic candidate sequence. Each new player receives the next unused separated static start region, and the player's dynamic home system connects to that region plus a redundant nearby static route when one is available. The assignment is persisted with the concrete galaxy so reconnects and save/reload do not reshuffle starts. If more players join than can satisfy the strict separation target, the server deterministically chooses the most separated remaining static system rather than rejecting the join.

## Multiplayer authority

Procedural hosts add a bounded versioned generation descriptor to the existing `GALAXY` packet. It carries the normalized generation settings, generation seed, and authoritative world seed. A client that initially bootstraps in legacy mode installs those server settings and rebuilds its local system environment before the normal authoritative galaxy snapshot path continues.

Legacy galaxy packets remain compatible because the existing 1/2-copy header is unchanged and the new descriptor is only emitted for procedural galaxies.

## Persistence

The generator is only responsible for initial creation. Server saves retain the concrete generated system IDs, template IDs, control/runtime state, resources, units, bases, projectiles, items, wormhole links, player-home mappings, and procedural start-region assignments. Restore therefore preserves an existing generated galaxy even if current generator settings or seeds differ from the ones that originally created it.

## Large-map simulation bounds

Procedural worlds do not update every inactive system every frame. `GalaxyInactiveSimulationScheduler` advances at most four inactive systems per frame in deterministic round-robin order and gives each selected system the accumulated elapsed time since its previous update. This bounds per-frame inactive-system work while keeping every system progressing over time. Dedicated multiplayer continues to use the server's authoritative scheduling path.

## Validation

`Issue383GalaxyGenerationValidator` covers:

- same-seed determinism;
- different-seed variation;
- every supported topology style;
- connectivity;
- duplicate/self-link rejection;
- generated-ID/template resolution;
- preview runtime-state isolation;
- deterministic candidate start-region selection;
- live player-home placement on separated start regions;
- malformed and oversized settings;
- maximum supported galaxy bounds;
- bounded inactive-system simulation on a maximum-size procedural galaxy;
- full concrete procedural save/reload state preservation under different current generator settings;
- authoritative server-to-client procedural generation synchronization.

The dedicated GitHub Actions workflow also compiles the project, runs the existing galaxy-connectivity and galaxy-wire regressions, and runs the real TCP multiplayer validator so host/client startup and authoritative convergence remain covered.
