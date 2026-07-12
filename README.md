# StarChem

StarChem is a Java 2D top-down multiplayer RTS prototype.

## Download

Download the release ZIP and extract it.

The player package contains the compiled `StarChem.jar`, the required `config`
folder, Windows and Linux launchers, and the packaged legal and quick-start documents.

On Windows, double-click `run-starchem.bat` to open the lobby and choose the game mode and connection options from the menu.

On Linux, open a terminal in the extracted folder and run:

```text
./run-starchem.sh
```

Java 17 or newer is required.

Players do not need Gradle, source files, or a local compile step.

## Dedicated Linux server

Start a headless dedicated server from the extracted release folder with:

```text
./run-starchem-server.sh
```

The launcher defaults to UDP port `50000` and the server name `StarChem-Server`. Override either value with environment variables and pass additional StarChem options after the script name:

```text
STARCHEM_PORT=50100 STARCHEM_SERVER_NAME="Public Server" ./run-starchem-server.sh --galaxy-copies 2
```

The equivalent direct Java command is:

```text
java -Djava.awt.headless=true -jar StarChem.jar --server 50000 --name StarChem-Server
```

Open or forward the selected **UDP** port. Stop the server with `Ctrl+C` or a normal `SIGTERM`; the server closes its network transport before the process exits. It prints a status line at startup and every 60 seconds while running.

Run `java -jar StarChem.jar --help` to view all supported startup options. Unknown options and missing option values are rejected instead of being silently ignored.

## Version

Run the following command from the extracted release folder to print the application version and build commit:

```text
java -jar StarChem.jar --version
```

Clients and servers should use the same StarChem release version.

## In-game reference menus

Press `I` during a game to open the resource catalog. The catalog lists every loaded material and shows the loaded star-system templates, configured system roles, and resource-node types where the selected raw resource can naturally appear. Produced and salvage materials are identified separately because they are not placed in natural system belts.

Press `M` to open the galaxy map. Press `I` again or `Escape` to close the resource catalog.

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

Create and push a semantic-version tag such as `vX.Y.Z`.

The release workflow derives the application version from the tag, embeds the commit SHA in `StarChem.jar`, runs the complete Gradle verification suite, smoke-tests the application and Linux headless server, verifies the package layout and legal notices, and publishes `StarChem-vX.Y.Z.zip` to the GitHub Release.
