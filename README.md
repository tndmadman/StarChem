# StarChem

StarChem is a Java 2D top-down multiplayer RTS prototype.

It is written in plain Java/Swing with a UDP multiplayer layer. The host is authoritative: it creates the session, assigns player names/colors, spawns each player's unit group, removes that group when the player leaves, and sends snapshots to reduce desync.

## Current features

- Top-down RTS camera
- Swing lobby screen for Solo, Host, and Join
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
- Direct Windows batch launchers
- Gradle build
- GitHub Actions CI

## Requirements

- Java 17 JDK+
- Gradle is optional. The Windows `.bat` files do not require Gradle.

## Windows quick launch

Recommended normal launcher:

```bat
open-lobby.bat
```

The lobby lets you choose:

- Solo
- Host Game
- Join Game

Fast local two-window test:

```bat
launch-both-local.bat
```

Or launch host/client separately:

```bat
run-host.bat
run-join-local.bat
```

Optional custom port and display names:

```bat
launch-both-local.bat 50001
run-host.bat 50001 "Tyler Host"
run-join-local.bat 127.0.0.1 50001 "Tyler Client"
```

For LAN play, run `open-lobby.bat` or `run-host.bat` on the host machine. On the joining machine, use the lobby or run:

```bat
run-join-local.bat HOST_LAN_IP 50000 "Player Name"
```

Make sure Windows Firewall allows inbound UDP on the host port.

If Windows says `javac` is not recognized, install a Java 17+ JDK and reopen Command Prompt or File Explorer before running the batch file again.

## Run solo

With the lobby:

```bat
open-lobby.bat
```

With Gradle:

```bash
gradle run
```

Without Gradle on Windows:

```bat
run-starchem.bat --solo --name Player
```

## Run host/client from terminal

Host:

```bash
gradle run --args="--host 50000 --name Host"
```

Client:

```bash
gradle run --args="--join 127.0.0.1 50000 --name Player"
```

Or on Windows, use:

```bat
launch-both-local.bat
```

## Controls

- `WASD` or arrow keys: pan camera
- Mouse wheel: zoom
- Left click: select one unit
- Left drag: box-select units
- Right click: move selected units

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
3. Add a proper in-lobby player list before starting a match.
4. Add fog of war.
5. Add resource harvesting.
6. Add unit production buildings.
7. Add combat/projectiles.
8. Add NAT traversal or relay fallback for internet play.
