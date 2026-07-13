# Configuration, Troubleshooting, and Development

## Configuration overview

`config/starchem.json` is the top-level manifest. In v1.1.0-alpha it declares rules version 14, the starting Prospector, the default Outpost, and paths to the release’s data files.

Configured groups:

- `config/materials.json`
- Six ship files under `config/ships/`
- `config/weapons.json`
- Four loadout files under `config/loadouts/`
- `config/stations.json`
- `config/resources.json`
- Fourteen system files under `config/systems/`
- `config/galaxy.json`
- `config/automation.json`
- Nine craftable recipe files under `config/craftables/`
- `config/research.json`
- `config/npcs.json`

## Release-critical configuration

Multiplayer compatibility includes a SHA-256 fingerprint of release-critical configuration files. Editing these files changes the fingerprint.

Consequences:

- An edited client cannot join an unedited server.
- An unedited client cannot join an edited server.
- Every participant must use byte-compatible release-critical configuration.
- Copying data files from `main` into the release folder can break compatibility even if the displayed application version remains v1.1.0-alpha.

Keep a clean release folder for normal multiplayer. Test modifications in a separate copy.

## Top-level manifest

The release manifest configures:

```json
{
  "rulesVersion": 14,
  "startingShipType": "prospector",
  "defaultStationType": "outpost",
  "files": {
    "materials": "config/materials.json",
    "ships": [],
    "weapons": "config/weapons.json",
    "loadouts": [],
    "stations": "config/stations.json",
    "resources": "config/resources.json",
    "systems": [],
    "galaxy": "config/galaxy.json",
    "automation": "config/automation.json",
    "craftables": [],
    "research": "config/research.json",
    "npcs": "config/npcs.json"
  }
}
```

The arrays in the real file list every release data file. Identifiers referenced across files must match exactly.

## Galaxy configuration

`config/galaxy.json` controls extra deterministic topology:

```json
{
  "topology": {
    "wanderingWormholePairs": 4
  }
}
```

Valid pair count: 0–32.

Changing the value affects newly created sessions. Existing session topology is not retroactively rebuilt by editing the file while the game is running.

## System definitions

A system definition contains:

- Stable `id`.
- Display `name`.
- `role` and `tags` used by cataloging and NPC eligibility.
- Width and height.
- Environmental `modifiers`.
- Declared spawn materials.
- Celestial bodies and parent/orbit relationships.
- Resource belts, node kind, orbit, width, arc, count, amount, harvest rate, radius, and weighted material composition.

Invalid material IDs, broken celestial parents, incomplete templates, or disconnected topology are covered by validators.

## Material definitions

Each material includes:

- Display name.
- UI color.
- Family.
- Rarity tier.
- Raw/manufactured status.

Raw status controls whether a material is expected to appear naturally. Manufactured and salvage materials should not be placed in ordinary belt composition without deliberately changing game rules.

## Recipes

Each craftable item can define:

- ID and display name.
- Description and category.
- Valid station types.
- Required research topics.
- Production time.
- Required resources.
- Output material and amount.

The release validates recipe references and dependency graphs. Circular dependencies or progression gates can still create design-level bootstrap problems even when every identifier is syntactically valid.

## Known v1.1.0-alpha bootstrap blocker

The tagged release configures this circular dependency:

- Advanced Industry requires a Research Lab.
- The Research Lab costs Radiation Shielding.
- Radiation Shielding requires Advanced Industry.

This prevents a clean non-developer game from entering the research tree through normal manufacturing alone. This is a release data issue, not a Java installation or networking problem.

Potential development-only workarounds include deliberately enabling developer free-crafting or changing the configuration in a test copy. Configuration edits must match on all multiplayer participants and are not part of the official unmodified release behavior.

## Startup command reference

```text
java -jar StarChem.jar
java -jar StarChem.jar --solo [options]
java -jar StarChem.jar --host PORT [options]
java -jar StarChem.jar --join HOST PORT [options]
java -Djava.awt.headless=true -jar StarChem.jar --server [PORT] [options]
```

Options:

```text
--name NAME
--system SYSTEM_ID
--galaxy-copies 1|2
--dev
--dev-token TOKEN
--enable-timers
--disable-timers
--version
--help
```

Unknown options and missing values terminate startup with an error and exit code 2.

## Developer access

Remote clients do not gain developer authority simply by adding `--dev`.

Example token-authorized host and client:

```text
java -jar StarChem.jar --host 50000 --dev --dev-token dev-token-0123456789abcdef
java -jar StarChem.jar --join HOST 50000 --dev --dev-token dev-token-0123456789abcdef
```

Token requirements:

- 16–128 characters.
- Letters, numbers, `.`, `_`, `~`, and `-` only.
- Use a strong random value.
- Never publish or commit the token.

A graphical host can grant or revoke a connected client’s requested developer access through the in-game Remote dev access section. A dedicated server requires token authorization even for loopback clients.

Developer mode exposes AI and performance overlays. `F3` toggles AI debug display and `F4` toggles performance metrics. Free crafting is a separately controlled developer state; `--dev` should not be treated as automatic unrestricted authority for a remote client.

## Build identity

Print the packaged identity:

```text
java -jar StarChem.jar --version
```

Release builds derive their version from the Git tag and embed the exact commit. Local builds use an identifiable development version.

Use both the semantic version and commit when reporting a bug.

## Common launch problems

### `java` is not recognized

Install Java 17 or newer and ensure the executable is available through `PATH`, or use an absolute Java path.

### Unsupported Java version

Check:

```text
java -version
```

An older system Java may be selected even when a newer runtime is installed.

### Missing configuration

Symptoms include missing-file errors or rule initialization failures. Extract the complete ZIP and launch from its root folder so `config/starchem.json` resolves correctly.

### Linux permission denied

```text
chmod +x run-starchem.sh run-starchem-server.sh
```

### JAR opens and closes immediately

Run it from a terminal to preserve the error output:

```text
java -jar StarChem.jar
```

## Common gameplay problems

### Research Lab cannot be constructed

Check the known bootstrap blocker above. In the tagged release, this can be the circular Radiation Shielding dependency rather than merely missing mining output.

### Build says resources are missing

Resources are stored in station hangars. A player may own enough material globally across ships and stations while the selected station still cannot access it. Unload or route the required materials to the producing station.

### Recipe is hidden or locked

Complete the listed research topic. Recipe gates are enforced independently from whether the output material exists elsewhere.

### Selected ship will not mine

- Prospector: rocks and gases.
- Deep Miner: rocks only.
- Gas Harvester: gases only.
- Other hulls: no harvesting unless explicitly configured.

### Deployer disappears

This is expected. The Deployer is consumed after station placement.

### Ships do not enter a wormhole

Move them directly into the gate. A right-click on the wormhole sends selected ships toward it. Transit occurs on contact and uses a cooldown to prevent immediate repeated transfer.

### Galaxy map shows a different system but ships did not travel

Viewing and physical ship transit are distinct. A remote system can be viewed without moving assets there.

## Common multiplayer problems

### Compatibility mismatch

Replace the client with a clean copy of the exact same release package used by the server. Do not mix tag builds, development builds, or edited JSON.

### Connection refused

Verify server readiness, address, TCP port, firewall, and router forwarding.

### UDP forwarding does not work

The release uses TCP only. Replace the rule with TCP forwarding.

### Duplicate player name

Use a unique name. Active-session takeover protection prevents a stale or second connection from silently replacing an active player.

### Frequent disconnects for one client

The server may isolate a client that cannot drain ordered traffic or exceeds queue limits. Check packet loss, host load, client load, and server performance logs.

## Dedicated server diagnostics

A healthy server prints:

- Build identity.
- Readiness.
- Initial status.
- Periodic status every 60 seconds.

It runs at a fixed 60 Hz with bounded catch-up. If it repeatedly falls behind, reduce host load and inspect CPU, memory, network, and system-count pressure.

## Validation and release hardening

The release’s automated checks cover areas including:

- Galaxy completeness and connectivity.
- Territory control.
- Material coverage and recipe dependency references.
- Progression gates.
- System modifiers.
- NPC expansion and per-system isolation.
- Background scheduling.
- Compatibility descriptors and environment synchronization.
- TCP framing, reconnects, slow-client isolation, and remote views.
- Celestial continuity.
- Linux server startup and shutdown.
- Legal notices and release packaging.

Process-level CI starts the packaged JAR in headless Linux mode, waits for TCP readiness, sends `SIGTERM`, and verifies a clean shutdown.

## Bug reports

Include:

- Exact `--version` output.
- Operating system and Java version.
- Solo, Host, Join, or Dedicated Server mode.
- Full launch command with secrets removed.
- Whether configuration was edited.
- Reproduction steps.
- Expected and actual result.
- Relevant client and server logs.
- Screenshots when the issue is visual.

Do not include developer tokens, private addresses that should remain private, or unrelated personal data.

## License and contributions

StarChem is proprietary and not open source. Public source visibility permits inspection, not copying, compiling, modifying, redistributing, publishing, selling, reusing, or incorporating protected material into another project without permission. Official unmodified compiled releases have limited personal, non-commercial permission under the packaged license. Outside implementation contributions are not currently accepted.