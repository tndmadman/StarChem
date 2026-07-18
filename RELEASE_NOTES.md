# StarChem v1.4.0

StarChem v1.4.0 is a major organized-NPC AI, production-planning, multiplayer-state, diagnostics, and release-hardening update built on v1.3.1.

## Organized NPC strategy and expeditions

- Added persistent faction-scoped runtime state, strategic modes, lifecycle resets, galaxy-wide capacity accounting, and deterministic recovery behavior.
- Added persistent cross-system expeditions with reservation, staging, wormhole transit, foothold construction, defense, completion, retreat, cancellation, and recovery phases.
- Added resilient expedition-readiness coordination so blocked launches report their reason and can resume when capacity, resources, or deployers become available.
- Corrected station-cap accounting so construction commitments and completed footholds are counted consistently and the final permitted expansion can launch.
- Added deterministic expedition supply-base selection and protected expedition formations and construction sites from wormhole and placement conflicts.

## NPC construction, recovery, logistics, and combat

- Added persistent NPC station-construction plans, station replacement, orphaned deployer recovery, and restoration of interrupted construction work.
- Added routed repair evacuation, recovery convoys, escort behavior for damaged ships and loaded deployers, and protection from conflicting combat or placement orders.
- Added distributed mobile-depot logistics, bounded depot placement, cargo routing, hauler coordination, and map-edge spacing safeguards.
- Added galaxy-wide worker-production failover and faction-scoped handling for workers, stations, deployers, support ships, and expedition commitments.
- Added squad-based Corsair combat coordination, balanced squad assignment, range management, recovery detachment, and fleet-wide projected-damage control.
- Preserved surplus deployer cargo during emergency station rebuilding.

## NPC resource planning

- Added strategic resource-budget categories for emergency fuel, worker recovery, station recovery, research, fleet growth, expansion, and general spending.
- Added recursive component planning and validation for organized-NPC construction, repairs, production, and expansion.
- Added developer-visible budget, strategy, expedition, recovery, and construction diagnostics.

## Auto-production allocation

- Fixed competing auto-production plans incorrectly treating the same stored or queued materials as available to every plan.
- Added a shared per-player planning ledger so prerequisite materials and future output are reserved across plans during each planning pass.
- Distributed prerequisite production across compatible idle stations instead of repeatedly selecting the same station.
- Prevented duplicate prerequisite jobs when previously queued output already covers a plan.
- Added regression coverage for competing plans, shared inventory, future output, and visible production roots.

## Multiplayer state and remote views

- Preserved approved remote-system views through automatic TCP reconnect and session resume.
- Derived remote-view mode from authoritative asset presence in full snapshots and resource corrections.
- Prevented loss of the final local asset from creating client-only fallback assets.
- Strengthened remote-system visibility and convergence validation under reconnect, view switching, snapshot traffic, server restart, and slow-client conditions.
- Improved dedicated-server lifecycle and TCP integration probes used by release validation.

## AI diagnostics

- Added persistent structured JSON Lines AI brain logging for authorized developer sessions.
- Moved JSON encoding, file writes, flushing, rotation, and retention to a dedicated asynchronous daemon writer.
- Added bounded non-blocking queues, reserved critical-event capacity, backpressure reporting, bounded shutdown draining, and clean disable/re-enable behavior.
- Isolated logging and filesystem failures from normal gameplay, dedicated servers, and remote clients.
- Added documented performance guardrails and repeated logger lifecycle validation.

## Validation and release engineering

- Added permanent organized-NPC AI validation and stress workflows.
- Added deterministic expedition seed sweeps and repeated recovery, reset, lifecycle, cross-system, defense, and logging validation.
- Expanded validation for station construction, deployer recovery, mobile depots, squad combat, resource budgets, worker failover, strategic stability, remote views, and TCP lifecycle behavior.
- Added a Windows dedicated-server launcher to the packaged release.
- Hardened the release workflow with tag/version/release-note consistency checks, byte-identical JAR and ZIP rebuild verification, SHA-256 artifacts, extracted-package testing, Linux server lifecycle testing, and Windows client/server launcher checks.
- Release publishing remains tag-only; release tags are not created, moved, or force-updated by the workflow.

## Compatibility

All multiplayer clients and servers must use the same v1.4.0 release package and matching packaged configuration files. Older builds are rejected by the compatibility handshake.

## Known limitation

- Galaxy-wide organized-NPC resource-budget planning still performs repeated full-system scans in some decision paths and has an overlapping intermediate-reservation edge case. This is tracked in GitHub issue #167. Normal validation passes, but very large long-running galaxies may experience additional AI-planning cost until that optimization is completed.

## Requirements

- Java 17 or newer.
- Extract the complete release ZIP before launching.
- Windows client: start with `run-starchem.bat`.
- Windows dedicated server: start with `run-starchem-server.bat`.
- Linux client: run `./run-starchem.sh`.
- Linux dedicated server: run `./run-starchem-server.sh`.
- Direct launch remains available through `java -jar StarChem.jar`.

## License

StarChem is proprietary software. Official unmodified compiled releases are licensed for personal, non-commercial use under the packaged `LICENSE`. Source code, configuration, assets, and other protected material may not be copied, modified, compiled, redistributed, sold, reused, or incorporated into another project without prior written permission.
