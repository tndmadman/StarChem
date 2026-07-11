# StarChem

StarChem is a Java 2D top-down multiplayer RTS prototype.

## Download

Download the release ZIP and extract it.

The player package contains the compiled StarChem JAR, the required config folder, and `run-starchem.bat`.

Double-click `run-starchem.bat` to open the lobby and choose the game mode and connection options from the menu.

Java 17 or newer is required.

Players do not need Gradle, source files, or a local compile step.

## Development

Run from source with Gradle during development.

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

Create and push tag v0.1.0 to build the first release ZIP.
