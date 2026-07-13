# StarChem v1.1.0-alpha

StarChem v1.1.0-alpha is a major galaxy, territory, economy, NPC, multiplayer, Linux-server, and release-hardening update built from 208 commits since v0.1.5.

> **Alpha release:** The core game and multiplayer systems are functional, but development is still active. Expect balance changes, incomplete features, compatibility breaks, and bugs. Please report reproducible problems through GitHub Issues.

## Highlights

- Added configurable persistent galaxies with one or two permanent instances of every registered static system template.
- Expanded the galaxy from seven to fourteen system templates.
- Added capturable and contestable star systems for players and NPC factions.
- Expanded the raw-resource economy from eight to twenty-four mineable materials.
- Added 56 manufactured intermediate materials and 60 JSON-driven recipes.
- Added organized NPC cross-system expeditions, funded footholds, and recursive component crafting.
- Added deterministic wandering wormholes and configurable galaxy topology.
- Replaced the old UDP multiplayer layer with hardened framed TCP synchronization.
- Added strict multiplayer build, rules, protocol, and configuration compatibility checks.
- Added a production-ready Linux headless server, Linux launchers, graceful shutdown, and process-level CI validation.
- Improved remote-system viewing, reconnect handling, celestial continuity, and background simulation.

## Static galaxy and topology

Hosts can create a galaxy containing one or two copies of every registered static system template. Protected player-home systems remain separate from capturable shared territory.

The galaxy keeps a permanent connected ring and fixed shortcuts. `config/galaxy.json` controls zero to thirty-two additional deterministic wandering-wormhole pairs. The same seed and configuration produce the same extra links.

Seven new system templates have been added:

- Binary Forge
- Volcanic Crucible
- Nebula Expanse
- Shattered Worlds
- Pulsar Reach
- Carbon Basin
- Ancient Graveyard

The original system templates have also been rebalanced for the expanded economy and progression.

## Territory control

Static systems now support neutral, capturing, controlled, contested, and protected control states.

Players and NPC factions can capture systems by maintaining sufficient influence inside the central command zone. Competing eligible forces contest the system and stop capture progress. Player-home systems remain protected.

Controlling a system grants modest mining-yield and shield-regeneration bonuses. The galaxy map displays the controller, control state, capture progress, and controller-colored system rings.

## Resources, manufacturing, and progression

The mineable resource catalog has expanded from eight to twenty-four materials across metal, mineral, volatile, and gas families. Materials now include data-driven display metadata, family, rarity, color, and raw-or-manufactured status.

The manufacturing economy adds 56 intermediate materials and 60 JSON recipes across processed materials, chemicals, electronics, industrial assemblies, power and defense, weapons, capital components, fuel, and salvage reclamation.

- Manufacturing Plant recipes are organized into category pages.
- Recipes can require completed research through `requiresResearch`.
- Prospectors, Deployers, and the first Manufacturing Plant remain directly craftable from raw resources to prevent progression deadlocks.
- Ship, station, research, capital, and megastructure costs progressively consume manufactured components.
- NPC industry recursively manufactures required subcomponents instead of stalling on advanced costs.
- Salvage-reclamation recipes recover steel plates, structural frames, and circuit boards.

System belts use weighted material compositions. An in-game resource catalog shows loaded materials, system templates, system roles, resource-node types, and where raw resources can naturally appear.

## NPC and simulation improvements

- NPC system eligibility is driven by system identity, role, and tags.
- NPC factions can capture and contest static systems.
- Organized NPC directors can launch cross-system expeditions and establish funded footholds.
- NPC runtime state and timers remain isolated per system.
- Background systems use hot, warm, and cold update tiers so quiet systems continue progressing without full foreground simulation frequency.
- System modifiers can affect mining, resource respawning, sensors, shields, movement, weapon range, and environmental damage.

## Multiplayer networking and synchronization

Multiplayer now uses framed TCP connections instead of UDP datagrams and the former custom reliability, acknowledgement, retry, duplicate-delivery, and packet-chunking systems.

- Added strict 4-byte length-prefixed UTF-8 framing with a 512 KB frame limit.
- Added bounded inbound and outbound queues, backpressure, coalescing by delivery class, and slow-client isolation.
- Added per-connection identities so stale socket events cannot disconnect or mutate replacement connections.
- Preserved session-token recovery, reconnect grace periods, takeover protection, token rotation, and automatic resume through a new TCP socket.
- Added real multi-client, dedicated-server, reconnect, slow-client, view-switching, and soak validation.
- Added revision-safe remote-system view requests so stale responses cannot replace the current authoritative view.
- Added complete ship and station visibility for approved non-adjacent system views.
- Split periodic resource correction from full system-view synchronization, preventing visible planet and moon snapping during normal corrections.

Multiplayer uses a strict compatibility descriptor containing the protocol version, application version, build commit, rules version, and a SHA-256 fingerprint of release-critical configuration files. Legacy, malformed, or mismatched clients are rejected before player or world state is created.

Internet hosts must allow inbound **TCP** traffic on the selected game port. StarChem multiplayer no longer uses UDP.

## Linux and dedicated-server operation

The release ZIP now includes `run-starchem.sh` for the Linux client and `run-starchem-server.sh` for a headless dedicated server.

The dedicated server now uses a fixed 60 Hz simulation clock with bounded catch-up, prints readiness and periodic status lines, validates startup options, and shuts down its network transport cleanly on `Ctrl+C` or `SIGTERM`. The server launcher supports `STARCHEM_PORT`, `STARCHEM_SERVER_NAME`, and `JAVA_BIN` environment overrides.

CI starts the actual packaged JAR in headless Linux mode, waits for TCP readiness, sends `SIGTERM`, and verifies a clean shutdown.

## Build, validation, and release tooling

- Expanded automated validation for galaxy completeness and connectivity, territory control, raw and manufactured materials, crafting dependency graphs, progression gates, system modifiers, NPC expansion, background scheduling, multiplayer compatibility, client environment synchronization, TCP framing, reconnects, remote views, celestial continuity, Linux server lifecycle, legal notices, and packaging.
- Added a deterministic TCP multiplayer smoke soak to normal verification and a configurable scheduled/manual extended soak workflow.
- Release builds derive their version from the Git tag and embed the exact build commit.
- `java -jar StarChem.jar --version` reports the packaged application version and shortened commit.
- `java -jar StarChem.jar --help` documents supported client, host, and dedicated-server options.
- CI verifies overridden release identity through Gradle, JAR metadata, artifact naming, runtime output, package layout, and legal notices.
- Release ZIPs include the compiled JAR, configuration, Windows launcher, Linux client and server launchers, README, license, and third-party notices.

## Compatibility

StarChem v0.1.5 clients are not compatible with v1.1.0-alpha multiplayer servers. All participating clients and servers must use the same v1.1.0-alpha release package and matching packaged configuration files.

## License

StarChem is proprietary software and is not open source. Official unmodified compiled releases are licensed only for personal, non-commercial use. The source code, rules data, assets, and other protected material may not be copied, compiled, modified, redistributed, sold, reused, or incorporated into another project without prior written permission. See the packaged `LICENSE` for the complete terms.

## Requirements

- Java 17 or newer.
- Extract the complete release ZIP before launching.
- Windows: start the game with `run-starchem.bat`.
- Linux client: run `./run-starchem.sh`.
- Linux dedicated server: run `./run-starchem-server.sh`.
- Direct launch remains available through `java -jar StarChem.jar`.
