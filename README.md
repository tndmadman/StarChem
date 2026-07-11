# StarChem

StarChem is a Java 2D top-down multiplayer RTS prototype.

## Download

Download the release ZIP and extract it.

The player package contains only the compiled `StarChem.jar`, the required `config` folder, and `run-starchem.bat`.

Double-click `run-starchem.bat` to open the lobby and choose the game mode and connection options from the menu.

Java 17 or newer is required.

Players do not need Gradle, source files, or a local compile step.

## Version

Run the following command from the extracted release folder to print the application version and build commit:

```text
java -jar StarChem.jar --version
```

Clients and servers should use the same StarChem release version.

## Galaxy topology

`config/galaxy.json` controls the number of extra seeded shortcuts added on top of the permanent connected ring:

```json
{
  "topology": {
    "wanderingWormholePairs": 4
  }
}
```

Set `wanderingWormholePairs` to `0` to keep only the base topology. Accepted values are `0` through `32`. The same galaxy seed and setting produce the same additional links. Multiplayer clients should use the same packaged configuration as the host.

## Development

Run from source with Gradle during development.

Local builds default to the identifiable development version `0.1.5-dev`. A release build receives its semantic version and commit SHA from the release workflow.

### Remote developer access

Remote clients never receive developer authority solely because they launch with `--dev`.

To deliberately authorize remote developer tools, start the host and client with the same strong token:

```text
java -jar StarChem.jar --host 50000 --dev --dev-token dev-token-0123456789abcdef
java -jar StarChem.jar --join HOST 50000 --dev --dev-token dev-token-0123456789abcdef
```

Tokens must contain 16-128 letters, numbers, `.`, `_`, `~`, or `-`. Use a random token and do not publish it. A graphical host's loopback client remains authorized automatically; dedicated servers require the token even for loopback clients.

A graphical host can also grant or revoke a connected client's requested developer access from the **Remote dev access** section of the in-game dev crafting panel. Revocation takes effect immediately on the client and server.

## Release

Create and push a semantic-version tag such as `v0.1.5`.

The release workflow derives the application version from the tag, embeds the commit SHA in `StarChem.jar`, runs the complete Gradle verification suite, smoke-tests `--version`, verifies the package layout, and publishes `StarChem-v0.1.5.zip` to the GitHub Release.
