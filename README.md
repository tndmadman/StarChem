# StarChem

StarChem is a Java 2D top-down multiplayer RTS prototype.

It is written in plain Java/Swing with a UDP multiplayer layer. The game starts in an in-game lobby inside the same main window, then swaps into the RTS view when you choose Solo, Host, or Join.

## Playable right now

- In-game lobby
- Solo, host, and join flow
- One starter ship per player
- Default station per player
- Finite resource nodes with slow regeneration
- Ship inventory
- Station stockpile
- Manual harvesting with `F1`
- Auto-unload when a cargo ship is near its station
- Build another starter ship with `B` once the station has enough resources
- Movement route line, destination ring, speed, and ETA
- Automatic fleet camera/zoom
- UDP host/client synchronization for players, ships, cargo, resources, and station stockpiles

## Current controls

- Left click your ship: select ship
- Left click a rock/cloud: target resource
- Left drag: box-select ships
- Right click: move selected ships
- `F1`: begin harvesting selected target with selected ship
- Move cargo ship near station: unload automatically
- `B`: build a new ship if the station stockpile has enough resources
- `ESC`: return to lobby

## First playable progression loop

1. Select your command ship.
2. Select an iron asteroid.
3. Move into range.
4. Press `F1` to harvest iron.
5. Return to your station and let the ship unload automatically.
6. Repeat with copper.
7. When the station has `80 Iron + 40 Copper`, press `B` to build another starter ship.

## Modding rules config

The rules file is:

```text
config/starchem-rules.json
```

This file is the intended rules source for moddable gameplay. It now describes:

- Materials
- Ship types
- Station types
- Starting ship type
- Default station type
- Ship HP
- Ship speed
- Ship cargo capacity
- Ship harvest range
- Ship orbit behavior
- Ship build cost by material
- Which node types a ship can harvest
- Station unload range
- Station unload rate
- Station build radius
- Which ships a station can build
- Which station packages a station can fabricate
- Which ship type can carry a packed station
- Whether the carrier ship is removed after placing a station
- Resource respawn behavior

Important: the JSON exists now, but the Java runtime is not fully wired to it yet. The next code patch needs to load this file, replace the hardcoded traits, and sync the host rules JSON to joining clients.

## Config-defined ship types

### Prospector

The current starter miner.

- Can harvest rocks and gas clouds
- Has cargo space
- Intended to auto-harvest after right-clicking a resource node
- Intended to return to station when full
- Intended to orbit while harvesting
- Intended to orbit near station while idle

### Deployer

The config key is `station_builder`, but the in-game display name is `Deployer`.

Purpose:

- Freighter-like station placement ship
- Built separately from normal ships
- Carries one packed station package
- Can carry the Shipyard package
- Is removed after placing the station

### Hauler

Future cargo ship unlocked by the Shipyard.

### Scout

Future fast recon ship unlocked by the Shipyard.

## Config-defined station types

### Outpost

The default station.

It can build:

- Prospector
- Deployer

It can fabricate station packages:

- Shipyard package

### Shipyard

Expanded production station.

Intended loop:

1. Outpost fabricates a packed Shipyard package.
2. Deployer loads the package.
3. Deployer moves to the desired placement location.
4. Player places the Shipyard.
5. Deployer is removed after placement.
6. Shipyard unlocks larger ships.

The Shipyard can build:

- Prospector
- Deployer
- Hauler
- Scout

## Requested next gameplay changes

These are now represented in the JSON config and need runtime wiring next:

- Right-clicking a resource node with a selected ship should begin auto-harvesting.
- Ships should automatically return to station when full.
- Ships should unload automatically at station.
- Idle ships near station should wander/orbit close to the station.
- Ships harvesting a rock/cloud should orbit around the target while mining.
- Depleted resource nodes should disappear, then respawn somewhere else instead of regenerating in place.
- Outpost should fabricate a packed Shipyard package.
- Deployer should load the packed Shipyard.
- Placing the Shipyard should remove the Deployer.
- Shipyard should unlock Hauler and Scout.

## Network/rules target architecture

- Host loads `config/starchem-rules.json`.
- Host is the authority for match rules.
- Clients joining the host receive the host's rules config.
- Clients use the host's config for ship/station traits and costs.
- This makes custom ships, stations, costs, and balancing moddable without editing Java every time.

## Windows launch

Double-click:

```bat
run-starchem.bat
```

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

## Next build steps

1. Load `config/starchem-rules.json` at runtime.
2. Replace hardcoded ship/station constants with loaded rules.
3. Send host rules JSON to clients on join through reliable packets.
4. Add auto-harvest on selected ship + right-click resource.
5. Add full-cargo return-to-station behavior.
6. Add station idle orbit and resource harvest orbit behavior.
7. Change static resource regeneration into despawn-and-respawn-somewhere-else.
8. Add station package fabrication.
9. Add Deployer package loading and Shipyard placement.
10. Remove Deployer after placing Shipyard.
11. Add a build menu for ship/station package choices.
12. Add build queues/timers instead of instant construction.
13. Add packet ordering checks so old reliable snapshots cannot overwrite newer state.
14. Add reconnect support.
15. Add fog of war.
16. Add combat/projectiles.
17. Add NAT traversal or relay fallback for internet play.
