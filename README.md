# StarChem

StarChem is a Java 2D top-down multiplayer RTS prototype.

It is written in plain Java/Swing with a UDP multiplayer layer. The game starts in an in-game lobby inside the same main window, then swaps into the RTS view when you choose Solo, Host, or Join.

## Current features

- Polished in-game menu with a space/RTS style
- Top-down RTS camera
- Automatic fleet camera/zoom based on the player's ships
- Starts with one command ship per player
- Default station for each player
- Automatic cargo unloading when a ship is inside station range
- Station stockpile for unloaded resources
- First real progression loop: build a second ship from station resources
- `B` build action for new ships
- New ship cost: `80 Iron + 40 Copper`
- Centered move orders so a single selected ship lands exactly on the clicked point
- Movement route line from ship to destination
- Destination ring/crosshair with speed and ETA readout
- Finite resource nodes with slow regeneration
- Silicate rocks containing iron, copper, silicates, and water ice
- Gas clouds containing hydrogen, helium, methane, and ammonia
- `F1` harvesting action
- Harvest beam/particle visuals while mining or collecting gas
- Unload beam/visual when transferring cargo to station
- Per-ship cargo inventory
- Selected ship inventory panel
- Selected resource information panel
- Station stockpile panel
- Modding rules file: `config/starchem-rules.json`
- Rules file describes materials, ship types, station types, traits, and build costs
- Single Windows launcher: `run-starchem.bat`
- Mouse box selection
- Right-click movement commands
- UDP host/client multiplayer
- Host-created player groups
- Player removal on leave or timeout
- Unique player names and colors
- Host snapshot sync for units, cargo, resource amounts, and station stockpiles
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
- Move cargo ship near station: unload automatically
- `B`: build a new ship if the station stockpile has enough resources
- `ESC`: return to lobby

## First progression loop

The first real RTS loop is now playable:

1. Select your command ship.
2. Select an iron asteroid.
3. Move into range.
4. Press `F1` to harvest iron.
5. Return to your station and let the ship unload automatically.
6. Repeat with copper.
7. When the station has `80 Iron + 40 Copper`, press `B` to build another ship.

The first build cost uses iron for the hull and copper for basic electronics/power routing.

## Modding rules config

The first modding rules file is now in:

```text
config/starchem-rules.json
```

That JSON is meant to become the main rules/config source for the match.

It currently describes:

- `materials`
- `shipTypes`
- `stationTypes`
- starting ship type
- default station type
- ship speed
- ship HP
- ship cargo capacity
- ship harvest range
- ship build costs
- which node types a ship can harvest
- station unload range
- station unload rate
- station build radius
- which ship types a station can build

Example ship type:

```json
"prospector": {
  "displayName": "Prospector",
  "role": "starter miner",
  "description": "Small starter utility ship. Cheap, slow, and useful for basic mining and hauling.",
  "maxHp": 100,
  "speed": 185,
  "cargoCapacity": 120,
  "harvestRange": 105,
  "buildTimeSeconds": 0,
  "buildCost": {
    "IRON": 80,
    "COPPER": 40
  },
  "canHarvest": ["SILICATE_ROCK", "GAS_CLOUD"]
}
```

Example station type:

```json
"outpost": {
  "displayName": "Outpost",
  "description": "Default starter station. Stores unloaded materials and builds basic ships.",
  "maxHp": 1200,
  "unloadRange": 118,
  "unloadRate": 95,
  "buildRadius": 72,
  "canBuildShips": ["prospector"]
}
```

Target architecture:

- Host loads `config/starchem-rules.json`.
- Host is the authority for match rules.
- Clients joining the host receive the host's rules config.
- Clients use the host's config for ship/station traits and costs.
- This makes custom ships, stations, costs, and balancing moddable without editing Java every time.

## Stations

Each player gets a default station near their spawn point.

Station behavior:

- Draws an unload radius around itself.
- Automatically unloads cargo from friendly ships in range.
- Stores unloaded resources in that player's station stockpile.
- Shows total station stockpile in the economy panel.
- Spawns newly built ships nearby.

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
- Cargo must be unloaded at station before it can be spent.

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
- Clients send movement, harvest, and build requests to the host.
- Host validates movement, harvesting, building, unloading, and stockpiles.
- Host syncs unit cargo, resource node amounts, station stockpiles, and ships.
- Host also sends periodic reliable full snapshots.
- Reliable messages are wrapped as `REL|messageId|payload`.
- Receivers answer with `ACK|messageId`.
- Unacked reliable messages are resent for a limited number of attempts.
- Clients send heartbeat pings.
- Host removes a player's group when it receives reliable `LEAVE` or the client times out.

Next network/config step: host-authoritative JSON rules sync over the existing reliable packet layer.

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

1. Wire `config/starchem-rules.json` into Java runtime loading.
2. Send host rules JSON to clients on join through reliable packets.
3. Replace hardcoded ship/station constants with loaded ship/station definitions.
4. Add build queues/timers instead of instant ship construction.
5. Add different ship classes: miner, scout, hauler, fighter.
6. Add a refinery/base upgrade system.
7. Add packet ordering checks so old reliable snapshots cannot overwrite newer state.
8. Add reconnect support.
9. Add a proper pre-match player list inside the lobby.
10. Add fog of war.
11. Add unit production buildings.
12. Add combat/projectiles.
13. Add NAT traversal or relay fallback for internet play.
