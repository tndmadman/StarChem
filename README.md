# StarChem

StarChem is a Java 2D top-down multiplayer RTS prototype.

It is written in plain Java/Swing with a UDP multiplayer layer. The game now starts in an in-game lobby inside the same main window, then swaps into the RTS view when you choose Solo, Host, or Join.

## Current features

- Top-down RTS camera
- In-game Swing lobby screen for Solo, Host, and Join
- Single Windows launcher: `run-starchem.bat`
- Grid map and resource nodes
- Local units
- Mouse box selection
- Right-click movement commands
- UDP host/client multiplayer
- Host-created player groups
- Player removal on leave or timeout
- Unique player names and colors
- Host snapshot sync to reduce desync
- Reliable UDP wrapper for important packets
- ACK/retry delivery for join, welcome, leave, remove, and full snapshots
- Fast lightweight snapshots for movement smoothing
- Gradle build
- GitHub Actions CI

## Requirements

- Java 17 JDK+
- Gradle is optional. The Windows `.bat` launcher does not require Gradle.

## Windows launch

Double-click:

```bat
run-starchem.bat
```

That opens the StarChem game window directly into the lobby.

The lobby lets you choose:

- Solo
- Host Game
- Join Game

Press `ESC` while in-game to shut down the current session and return to the lobby.

If Windows says `javac` is not recognized, install a Java 17+ JDK and reopen Command Prompt or File Explorer before running the batch file again.

## Run from terminal

Lobby:

```bash
gradle run
```

Solo directly:

```bash
gradle run --args="--solo --name Player"
```

Host directly:

```bash
gradle run --args="--host 50000 --name Host"
```

Client directly:

```bash
gradle run --args="--join 127.0.0.1 50000 --name Player"
```

## Controls

- `WASD` or arrow keys: pan camera
- Mouse wheel: zoom
- Left click: select one unit
- Left drag: box-select units
- Right click: move selected units
- `ESC`: return to lobby

## Network design

The current multiplayer model is host-authoritative UDP:

- Host opens the session on a known UDP port.
- Clients send reliable `JOIN|name` messages.
- Host assigns each client a player ID, unique name, color, and unit group.
- Clients send movement requests to the host.
- Host applies valid moves and broadcasts fast snapshots.
- Host also sends periodic reliable full snapshots.
- Reliable messages are wrapped as `REL|messageId|payload`.
- Receivers answer with `ACK|messageId`.
- Unacked reliable messages are resent for a limited number of attempts.
- Clients send heartbeat pings.
- Host removes a player's group when it receives reliable `LEAVE` or the client times out.

This is much more stable than the first two-peer prototype, but it is still not a finished internet multiplayer stack. NAT traversal, packet ordering windows, reconnects, and cheat resistance still need work.

## Next build steps

1. Add packet ordering checks so old reliable snapshots cannot overwrite newer state.
2. Add reconnect support.
3. Add a proper pre-match player list inside the lobby.
4. Add fog of war.
5. Add resource harvesting.
6. Add unit production buildings.
7. Add combat/projectiles.
8. Add NAT traversal or relay fallback for internet play.
