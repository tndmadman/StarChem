# StarChem

StarChem is a Java 2D top-down multiplayer RTS prototype.

## Download

Download the release ZIP and extract it.

The player package contains the compiled `StarChem.jar`, the required `config`
folder, `run-starchem.bat`, and the packaged legal and quick-start documents.

Double-click `run-starchem.bat` to open the lobby and choose the game mode and connection options from the menu.

Java 17 or newer is required.

Players do not need Gradle, source files, or a local compile step.

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

The release workflow derives the application version from the tag, embeds the commit SHA in `StarChem.jar`, runs the complete Gradle verification suite, smoke-tests `--version`, verifies the package layout and legal notices, and publishes `StarChem-vX.Y.Z.zip` to the GitHub Release.