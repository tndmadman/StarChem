# StarChem

StarChem is a Java 2D top-down multiplayer RTS prototype.

It is written in plain Java/Swing with a UDP multiplayer layer. The game starts in an in-game lobby inside the same main window, then swaps into the RTS view when you choose Solo, Host, or Join.

## Current features

- Polished in-game menu with a space/RTS style
- Top-down RTS camera
- Automatic fleet camera/zoom based on the player's ships
- Starts with one command ship per player
- Centered move orders so a single selected ship lands exactly on the clicked point
- Movement route line from ship to destination
- Destination ring/crosshair with speed and ETA readout
- Finite resource nodes with slow regeneration
- Silicate rocks containing iron, copper, silicates, and water ice
- Gas clouds containing hydrogen, helium, methane, and ammonia
- `F1` harvesting action
- Harvest beam/particle visuals while mining or collecting gas
- Per-ship cargo inventory
- Selected ship inventory panel
- Selected resource information panel
- Single Windows launcher: `run-starchem.bat`
- Mouse box selection
- Right-click movement commands
- UDP host/client multiplayer
- Host-created player groups
- Player removal on leave or timeout
- Unique player names and colors
- Host snapshot sync for units, cargo, and resource amounts
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

## Controls

- Left click your ship: select ship
- Left click a rock/cloud: target resource
- Left drag: box-select ships
- Right click: move selected ships
- `F1`: begin harvesting selected target with selected ship
- `ESC`: return to lobby

## Resource harvesting

The harvesting loop is deliberately simple and RTS-like:

1. Select one of your ships.
2. Select an asteroid or gas cloud.
3. Move the ship into range if needed.
4. Press `F1` to begin harvesting.

Rules:

- The ship must be selected.
- The asteroid/gas cloud must be selected.
- The ship must be in range.
- The ship must have free cargo space.
- The resource node must have material remaining.
- Each ship has its own cargo inventory.

Resource nodes are finite, but they slowly regenerate over time instead of being permanently exhausted.

## Materials

Silicate rocks can contain:

- Iron
- Copper
- Silicates
- Water ice

Gas clouds can contain:

- Hydrogen
- Helium
- Methane
- Ammonia

## Movement quality-of-life

- A single selected ship centers exactly on the clicked destination.
- Multi-ship formations are centered around the clicked point instead of offset from it.
- Moving ships draw a subtle line from the ship to its destination.
- The destination is marked with a small ring/crosshair.
- Local ships show speed and ETA while moving.

## Camera behavior

The camera no longer depends on manual mouse-wheel zoom. It frames the player's owned ships automatically:

- With one ship, it uses a comfortable minimum close zoom so the camera does not zoom too far in.
- As the player's ships spread out, the camera smoothly zooms out to keep all owned ships in frame.
- When future resource systems add more units, the camera will scale with the fleet automatically.

## Network design

The current multiplayer model is host-authoritative UDP:

- Host opens the session on a known UDP port.
- Clients send reliable `JOIN|name` messages.
- Host assigns each client a player ID, unique name, color, and unit group.
- Clients send movement and harvest requests to the host.
- Host validates movement/harvesting and broadcasts snapshots.
- Host syncs unit cargo and resource node amounts.
- Host also sends periodic reliable full snapshots.
- Reliable messages are wrapped as `REL|messageId|payload`.
- Receivers answer with `ACK|messageId`.
- Unacked reliable messages are resent for a limited number of attempts.
- Clients send heartbeat pings.
- Host removes a player's group when it receives reliable `LEAVE` or the client times out.

This is much more stable than the first two-peer prototype, but it is still not a finished internet multiplayer stack. NAT traversal, packet ordering windows, reconnects, and cheat resistance still need work.

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

## Next build steps

1. Add a way to spend resources to build more ships.
2. Add a basic refinery/base/drop-off point.
3. Add packet ordering checks so old reliable snapshots cannot overwrite newer state.
4. Add reconnect support.
5. Add a proper pre-match player list inside the lobby.
6. Add fog of war.
7. Add unit production buildings.
8. Add combat/projectiles.
9. Add NAT traversal or relay fallback for internet play.
