# StarChem v0.2.0

StarChem v0.2.0 is a major galaxy, territory, economy, multiplayer compatibility, and release-hardening update.

## Highlights

- Added configurable persistent galaxies with one or two permanent instances of every registered system template.
- Expanded the galaxy from seven to fourteen system templates.
- Added capturable and contestable static star systems for players and NPC factions.
- Expanded the raw resource economy from eight to twenty-four mineable materials.
- Added organized NPC cross-system expeditions and funded footholds.
- Added deterministic wandering wormholes and configurable galaxy topology.
- Added strict multiplayer build, rules, protocol, and configuration compatibility checks.
- Improved cross-system client environment synchronization and background simulation.

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

Controlling a system grants modest mining-yield and shield-regeneration bonuses. The galaxy map now displays the controller, control state, capture progress, and controller-colored system rings.

## Resources and progression

The mineable resource catalog has expanded from eight to twenty-four materials across metal, mineral, volatile, and gas families. Materials now include family, rarity, color, and raw-or-processed metadata.

System belts use weighted material compositions. Ship, station, research, capital, and megastructure requirements have been updated to use the expanded resource economy while keeping starter systems progression-complete.

## NPC and simulation improvements

- NPC system eligibility is now driven by system identity, role, and tags.
- NPC factions can capture and contest static systems.
- Organized NPC directors can launch cross-system expeditions and establish funded footholds.
- NPC runtime state and timers remain isolated per system.
- Background systems use hot, warm, and cold update tiers so quiet systems continue progressing without receiving full foreground simulation frequency.
- System modifiers can affect mining, resource respawning, sensors, shields, movement, weapon range, and environmental damage.

## Multiplayer and synchronization

Multiplayer now uses a strict compatibility descriptor containing the protocol version, application version, build commit, rules version, and a SHA-256 fingerprint of release-critical configuration files.

Legacy, malformed, or mismatched clients are rejected before player or world state is created. Clients and servers must use matching v0.2.0 builds and compatible packaged configuration files.

Client synchronization now tracks exact galaxy system-instance identities, including duplicate template copies and player homes. Celestial bodies and resource orbits continue moving between sparse snapshots, with periodic complete resource corrections to prevent drift.

## Build, validation, and release tooling

- Expanded automated validation for galaxy completeness and connectivity, territory control, materials, system modifiers, NPC expansion, background scheduling, multiplayer compatibility, client environment synchronization, and legal notices.
- Release builds derive their version from the Git tag and embed the exact build commit.
- `java -jar StarChem.jar --version` reports the packaged application version and shortened commit.
- CI verifies overridden release identity through Gradle, JAR metadata, artifact naming, and runtime output.
- Release ZIPs include the compiled JAR, configuration, launcher, README, license, and third-party notices.

## License

StarChem is proprietary software and is not open source. Official unmodified compiled releases are licensed only for personal, non-commercial use. The source code, rules data, assets, and other protected material may not be copied, compiled, modified, redistributed, sold, reused, or incorporated into another project without prior written permission. See the packaged `LICENSE` for the complete terms.

## Compatibility

StarChem v0.1.5 clients are not compatible with v0.2.0 multiplayer servers. All participating clients and servers should use the same StarChem v0.2.0 release package.

## Requirements

- Java 17 or newer.
- Extract the complete release ZIP before launching.
- Start the game using `run-starchem.bat` or `java -jar StarChem.jar` from the extracted StarChem folder.
