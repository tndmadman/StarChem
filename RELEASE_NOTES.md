# StarChem v1.4.0

StarChem v1.4.0 is a major organized-NPC AI, interface, multiplayer-audio, production, diagnostics, and release-hardening update.

## Organized NPC factions and expeditions

- Added persistent faction-scoped runtime state, lifecycle reset handling, strategic states, capacity accounting, and deterministic faction recovery.
- Added galaxy-wide organized NPC expeditions with reservation, staging, wormhole transit, foothold deployment, defense, completion, retreat, and recovery phases.
- Added strategic station construction, mobile depots, station replacement, deployer recovery, repair evacuation, worker production, and squad-combat coordination.
- NPC factions now reserve resources by strategic priority for emergency fuel, workers, station recovery, research, fleet growth, and expansion.
- Added deterministic selection of expedition supply bases and corrected station-cap accounting so the final allowed foothold can be launched.
- Preserved deployer cargo during emergency rebuilding and protected active construction and recovery units from conflicting orders.
- Added escort behavior for damaged ships and loaded deployers retreating for repairs or recovery.
- Improved station placement around map edges, wormholes, existing structures, and active expedition formations.

## Tactical reference and navigation tools

- Added a tactical minimap showing resources, wormholes, friendly assets, hostile contacts, the current camera view, and temporary event pings.
- The minimap supports click-to-pan navigation while respecting overlapping build and developer panels.
- Added an F1 searchable codex generated from loaded rules for ships, stations, resources, research, manufacturing, NPC factions, and controls.
- Expanded the resource catalog into searchable material and star-system tabs.
- Added graphical ship, station, material, orbit, and system previews to the codex and catalog.
- Preserved normal text entry while searching so gameplay shortcut keys do not consume typed letters.

## Multiplayer, remote views, and game audio

- Added a server-authoritative audio event stream for multiplayer simulation events.
- Production completion, station placement, pickup, tractor, wormhole, and related effects are delivered to the correct owning client.
- Prevented duplicate client-side wormhole audio and primed event cursors so clients do not replay stale sounds after joining.
- Preserved approved remote-system views through automatic TCP reconnect and session resume.
- Full snapshots and resource corrections now derive remote-view state from authoritative asset presence.
- Prevented removal of the final local asset from creating client-only fallback assets.
- Strengthened remote-system visibility, reconnect churn, snapshot hardening, slow-client isolation, and dedicated-server lifecycle validation.

## Production, logistics, fuel, and progression

- Fixed auto-production allocation when multiple plans compete for compatible stations and resources.
- Added regression coverage for visible production roots and competing plan allocation.
- Improved hauler routing, fuel-shuttle behavior, station fueling, and automatic Research Lab resupply.
- Kept Salvagers within reliable station unload range while idle.
- Expanded system and resource catalog data used by production and discovery interfaces.
- Continued synchronizing queue state, research completion, and production results through authoritative snapshots.

## AI diagnostics and developer tooling

- Added persistent structured AI brain logging for authorized developer sessions.
- Moved JSON encoding, disk writes, flushing, rotation, and retention to a dedicated asynchronous writer.
- Added a bounded non-blocking queue, reserved critical-event capacity, backpressure reporting, bounded shutdown draining, and clean re-enable behavior.
- Logging filesystem failures remain isolated from normal gameplay, dedicated servers, and remote clients.
- Expanded developer snapshots and commands for faction strategy, budgets, expeditions, recovery, construction, and cross-system operations.

## Validation and release engineering

- Added dedicated organized-NPC AI validation and repeated AI stress workflows.
- Added deterministic expedition seed sweeps and repeated lifecycle, recovery, reset, cross-system, and logging validation.
- Expanded the standard Gradle verification suite with minimap, codex, NPC expedition, station construction, mobile depot, recovery, combat, resource-budget, and faction-lifecycle validators.
- Release builds require release-note, semantic-version, and tag consistency.
- Release validation rebuilds the JAR and ZIP and requires byte-identical output.
- Every release publishes a SHA-256 checksum and validates the extracted Linux package and both Windows launchers.
- Added a Windows dedicated-server launcher alongside the existing Windows client and Linux client/server launchers.

## Compatibility

All multiplayer clients and servers must use the same v1.4.0 release package and matching packaged configuration files. Older builds are rejected by the compatibility handshake.

## Known limitation

- Galaxy-wide organized-NPC resource-budget planning still performs repeated full-system scans in some decision paths and has an overlapping intermediate-reservation edge case. This is tracked in GitHub issue #167. Normal validation passes, but very large long-running galaxies may experience extra AI planning cost until that optimization is completed.

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
