# StarChem

StarChem is a Java 2D top-down multiplayer RTS prototype.

It is written in plain Java/Swing with a simple UDP peer-to-peer networking layer. This is a starter foundation, not a finished game yet.

## Current features

- Top-down RTS camera
- Grid map and resource nodes
- Local units
- Mouse box selection
- Right-click movement commands
- Basic UDP P2P connection
- Remote peer spawning
- Remote movement command replication
- Gradle build
- GitHub Actions CI

## Requirements

- Java 17+
- Gradle, or your IDE's Gradle support

## Windows quick launch

Double-click this to launch both local test clients:

```bat
launch-both-local.bat
```

Or launch them separately:

```bat
run-host.bat
run-join-local.bat
```

Optional custom port:

```bat
launch-both-local.bat 50001
run-host.bat 50001
run-join-local.bat 127.0.0.1 50001
```

For LAN play, run `run-host.bat` on the host machine. On the joining machine, run:

```bat
run-join-local.bat HOST_LAN_IP 50000
```

Make sure Windows Firewall allows inbound UDP on the host port.

## Run solo

```bash
gradle run
```

## Run two-player P2P locally

Terminal 1:

```bash
gradle run --args="--host 50000 --id HOST"
```

Terminal 2:

```bash
gradle run --args="--join 127.0.0.1 50000 --id JOIN"
```

## Run over LAN

On the host machine:

```bash
gradle run --args="--host 50000 --id HOST"
```

On the joining machine, replace `HOST_LAN_IP` with the host's LAN IP:

```bash
gradle run --args="--join HOST_LAN_IP 50000 --id JOIN"
```

Make sure Windows Firewall allows inbound UDP on the host port.

## Controls

- `WASD` or arrow keys: pan camera
- Mouse wheel: zoom
- Left click: select one unit
- Left drag: box-select units
- Right click: move selected units

## Network design

The multiplayer layer is intentionally simple right now:

- UDP socket per client
- Host waits on a known port
- Joiner sends `HELLO`
- Peers remember each other's address from received packets
- Movement commands are sent as `MOVE|playerId|unitId|x|y`

This is fine for LAN prototype testing. It is not cheat-proof and it does not solve NAT traversal yet.

## Next build steps

1. Deterministic unit IDs per player.
2. Lobby screen for host/join instead of command-line args.
3. Full state sync for late joiners.
4. Fog of war.
5. Resource harvesting.
6. Unit production buildings.
7. Combat/projectiles.
8. NAT traversal or relay fallback for internet play.
