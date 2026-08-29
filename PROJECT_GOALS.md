# StarChem Project Goals

This file records the project-level goals and engineering guardrails for the consolidated StarChem workstream. It is intentionally broader than any one GitHub issue so future work can be checked against the same direction.

## Core product goal

StarChem is a server-authoritative 2D top-down RTS space game. The long-term direction is a persistent, data-driven galaxy where tactical ship/station play and galaxy-scale strategy remain consistent in solo, hosted multiplayer, and dedicated-server play.

## Current consolidation goals

The integration branch `integration/starchem-functional-2026-08-27` exists to combine the recent work that was developed on several separate branches into one functional branch without discarding any prior implementation.

The consolidation pass includes these workstreams:

1. Dynamic galaxy events (#297): deterministic anomalies, derelicts, NPC encounters, environmental hazards, and temporary wormholes with discovery/FOW, persistence, bounded scheduling, and multiplayer-safe projection.
2. Observer sessions (#296): authenticated, server-authoritative read-only observer sessions with independent admission policy and visibility modes. This work is already represented in `main` through #357 and must not be duplicated or regressed.
3. Strategic empire overview (#298): owner-scoped cross-system strategic summaries and the player-facing empire overview overlay.
4. Fog-of-war performance work from `perf/salvage-fow-331`: visibility-frame reuse, fog rendering/persistence efficiency, and shared miner-assignment optimizations.
5. Wormhole discovery/radar behavior (#320): remembered wormhole presentation plus radar wormhole-search behavior and automation safety.
6. Production queue fix (#319): station deployment packages queued after ships must remain valid and execute correctly.

## Engineering guardrails

- Preserve server authority. Clients may request actions or receive projections, but authoritative state changes belong on the server/host simulation.
- Preserve fog-of-war and observer visibility boundaries. No optimization, strategic summary, event marker, wormhole shortcut, or observer mode may leak hidden state.
- Preserve deterministic and restart-safe state. Save/reload must not reroll events, duplicate entities, duplicate rewards, lose production queue intent, or corrupt cross-system state.
- Prefer existing game systems over parallel replacements. Dynamic events should orchestrate ordinary resources, items, NPC units, wormholes, notices, modifiers, and pickups.
- Keep background work bounded. Large galaxies must not force every system to simulate every frame.
- Keep network payloads bounded and validated. New rows/fields require explicit limits and malformed-input rejection.
- Preserve permanent topology. Temporary wormholes and remembered/FOW presentation must never delete or mutate required permanent wormholes.
- Avoid frame-rate full-galaxy scans. Strategic summaries and visibility work should use cached, dirty, or scheduled aggregation.
- Keep UI-only preferences out of authoritative server saves.
- Add regression coverage for fixes and cross-system behavior before considering the combined branch merge-ready.

## Preservation policy for this consolidation

No existing source branch is to be deleted as part of this pass. No previously implemented feature is to be intentionally removed merely to make a merge easier. If two implementations conflict semantically, the conflict must be documented in `INTEGRATION_LOG.md` and resolved by preserving both goals where possible. If preserving both is not technically sound, explicit user approval is required before intentionally dropping prior behavior.

## Definition of a functional integration branch

The consolidated branch is considered functional when:

- all intended unique changes from the listed source branches are represented;
- already-merged work is not duplicated or regressed;
- merge conflicts are resolved deliberately and documented;
- the project compiles;
- the normal Gradle verification suite passes;
- dedicated feature validators that are not yet in `gradle check` also pass;
- multiplayer/FOW/save/scheduler regressions remain green;
- remaining intentionally unfinished feature work is listed explicitly rather than silently omitted.

## Main-branch policy

This integration branch is not permission to rewrite or directly replace `main`. The intended flow is: consolidate -> validate -> review the combined diff -> make the integration branch merge-ready. Merging to `main` remains a separate explicit decision.
