# StarChem

StarChem is a Java 2D top-down multiplayer RTS prototype.

## Download

Download the release ZIP and its matching `.sha256` file, verify the checksum, then extract the complete ZIP.

The player package contains the compiled `StarChem.jar`, the required `config`
folder, Windows and Linux launchers, and the packaged legal and quick-start documents.

On Windows, double-click `run-starchem.bat` to open the lobby and choose the game mode and connection options from the menu.

On Linux, open a terminal in the extracted folder and run:

```text
./run-starchem.sh
```

Java 17 or newer is required.

Players do not need Gradle, source files, or a local compile step.

## Dedicated server

### Windows

Start a dedicated server from the extracted release folder with:

```text
run-starchem-server.bat
```

The launcher defaults to TCP port `50000` and the server name `StarChem-Server`. Override either value before launching:

```text
set STARCHEM_PORT=50100
set STARCHEM_SERVER_NAME=Public Server
run-starchem-server.bat --galaxy-copies 2
```

### Linux

Start a headless dedicated server from the extracted release folder with:

```text
./run-starchem-server.sh
```

Override its default port or name with environment variables and pass additional StarChem options after the script name:

```text
STARCHEM_PORT=50100 STARCHEM_SERVER_NAME="Public Server" ./run-starchem-server.sh --galaxy-copies 2
```

The equivalent direct Java command on either platform is:

```text
java -Djava.awt.headless=true -jar StarChem.jar --server 50000 --name StarChem-Server
```

Open or forward the selected **TCP** port. Stop the server with `Ctrl+C` or a normal termination signal; the server closes its network transport before the process exits. It prints a status line at startup and every 60 seconds while running.

### Console commands

When the dedicated server is attached to an interactive terminal, enter commands directly in that terminal. Input is queued and executed by the authoritative server tick instead of changing game state from the console-reader thread. Closing or redirecting standard input does not stop the server.

Available commands:

```text
help [command]  Show available commands or detailed command help.
status          Print server, network, save, and autosave status.
players         List connected and retained player sessions.
save            Write a manual server save.
version         Print the running build identity.
stop            Save and stop the server cleanly.
shutdown        Alias for stop.
```

Run `java -jar StarChem.jar --help` to view all supported startup options. Unknown options and missing option values are rejected instead of being silently ignored.

## Version

Run the following command from the extracted release folder to print the application version and build commit:

```text
java -jar StarChem.jar --version
```

Clients and servers should use the same StarChem release version.

## Multiplayer networking

StarChem multiplayer uses framed TCP connections. A host or dedicated server listens on the selected game port, and remote clients connect to that same host and port. Internet-hosted games must allow inbound TCP traffic on the selected port; StarChem no longer uses UDP for multiplayer.

## In-game reference menus

Press `F1` during a game, or choose **CODEX** in the lobby, to open the searchable StarChem codex. It is generated from the currently loaded rule definitions and covers ships, stations, resources, research prerequisites and unlocks, manufacturing recipes, NPC factions, and controls. Filter by category or search names, IDs, stats, costs, descriptions, and unlock text. The codex is read-only and works during solo, hosted, and joined games without changing game state.

Press `I` during a game to open the resource catalog. The catalog lists every loaded material and shows the loaded star-system templates, configured system roles, and resource-node types where the selected raw resource can naturally appear. Manufactured and salvage materials are identified separately because they are not placed in natural system belts.

Press `M` to open the galaxy map. Press the active menu key again or `Escape` to close a reference overlay.

A tactical minimap appears in the lower-right corner during normal play. It shows resources, wormholes, friendly ships and bases, enemy contacts, and the current camera view. Click anywhere inside its map area to pan the camera there. Contact, wormhole, and friendly-loss pings briefly highlight important locations; build and developer panels take input priority if they overlap it.

## Manufacturing economy

StarChem uses a JSON-driven intermediate manufacturing economy. Material display metadata, family, rarity, color, and raw/manufactured status are loaded from `config/materials.json`. Manufacturing recipes are loaded from the files listed under `files.craftables` in `config/starchem.json`.

The Manufacturing Plant organizes recipes into processed materials, chemicals, electronics, industrial assemblies, power and defense, weapons, and capital systems. Recipes may require completed research through their `requiresResearch` field. Starter Prospectors, Deployers, and the first Manufacturing Plant remain directly craftable from raw resources so a new game cannot deadlock before manufacturing is available.

Salvage can be recycled through reclamation recipes, while ships, stations, and later research consume progressively more manufactured components instead of enormous flat piles of raw ore and gas.

## Galaxy topology

`config/galaxy.json` controls the number of extra seeded shortcuts added on top of the permanent connected ring:

```json
{
  "topology": {
    "wanderingWormholePairs": 4
  }
}
```

Set `wanderingWormholePairs` to `0` to keep only the base topology. Accepted values are `0` through `32`. The same galaxy seed and setting produce the same additional links. The host reads this setting when a session is created, so changing it requires starting a new session. Multiplayer clients should use the same packaged configuration as the host.

## Development

Run from source with Gradle during development.

Local builds use an identifiable `-dev` application version. Release builds receive their semantic version and commit SHA from the release workflow.

### Remote developer access

Remote clients never receive developer authority solely because they launch with `--dev`.

To deliberately authorize remote developer tools, start the host and client with the same strong token:

```text
java -jar StarChem.jar --host 50000 --dev --dev-token dev-token-0123456789abcdef
java -jar StarChem.jar --join HOST 50000 --dev --dev-token dev-token-0123456789abcdef
```

Tokens must contain 16-128 letters, numbers, `.`, `_`, `~`, or `-`. Use a random token and do not publish it. A graphical host's loopback client remains authorized automatically; dedicated servers require the token even for loopback clients.

A graphical host can also grant or revoke a connected client's requested developer access from the **Remote dev access** section of the in-game dev crafting panel. Revocation takes effect immediately on the client and server.

## License

StarChem is proprietary software. Copyright © 2026 tndmadman. All rights
reserved.

The source code is visible for inspection only. Public repository access does
not grant permission to copy, compile, modify, redistribute, publish, sell,
reuse, or incorporate StarChem code, rules data, assets, or other protected
material into another project.

Official unmodified compiled releases may be run only under the limited
personal, non-commercial permission stated in [`LICENSE`](LICENSE). StarChem is
not open source. Outside implementation contributions are not currently
accepted; see [`CONTRIBUTING.md`](CONTRIBUTING.md). Third-party notice policy is
documented in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Release

1. Update `RELEASE_NOTES.md` so its first line is exactly `# StarChem v<version>`.
2. Create and push that immutable semantic-version tag, for example `v1.1.0-alpha`.
3. The release workflow rebuilds the JAR twice and requires byte-identical output, runs the complete verification suite, creates the release ZIP twice and requires byte-identical output, verifies its SHA-256 checksum, smoke-tests the extracted Linux client and dedicated server, and validates both Windows launchers.
4. Only after every validation job passes does the tag-triggered publish job attach the ZIP and `.sha256` file to the GitHub Release.

The workflow never creates, moves, or force-updates a release tag. Pull requests that modify release-critical files run the same build and package validation without publishing anything.
