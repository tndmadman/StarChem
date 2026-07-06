# StarChem

StarChem is a Java 2D top-down multiplayer RTS prototype.

It is written in plain Java/Swing with a UDP host/client layer. The game opens into an in-game lobby, then swaps into the RTS view when you choose Solo, Host, or Join.

## Playable right now

- In-game lobby
- Solo, host, and join flow
- One starter Prospector per player
- Default Outpost station per player
- Ship inventory and station stockpile
- Destroyed ships and stations drop carried cargo, hangar contents, and salvage parts
- Loot pieces pop out, spin, drift, and slowly stop moving
- Dedicated Salvager ships use tractor beams to pull in and collect world loot
- Right-click resource auto-harvesting
- Full cargo return-to-station behavior
- Automatic unloading near station
- Idle ships orbit/wander near station
- Harvesting ships orbit the asteroid/cloud while working
- Depleted resource nodes vanish and respawn somewhere else
- Outposts build Prospectors and Deployers
- Outposts fabricate/load Shipyard, Research Lab, and Manufacturing Plant packages into Deployers
- Deployers place packaged stations and are consumed
- Shipyards unlock industrial, combat, capital, supercapital, titan, and monolith hulls
- Research Labs require Fuel in their hangar to stay powered
- Manufacturing Plants manufacture Fuel from harvested gases
- Craftable item recipes are loaded from their own JSON data files
- Movement route line, destination ring, speed, and ETA
- Manual WASD camera and mouse-wheel zoom
- UDP host/client synchronization for players, ships, stations, cargo, resources, world loot, and stockpiles

## Controls

- `W`: pan camera up
- `A`: pan camera left
- `S`: pan camera down
- `D`: pan camera right
- Mouse wheel: zoom camera in/out around the cursor
- Left click your ship: select ship
- Left click your base: open build menu
- Left click a loaded Deployer: open place-station menu
- Left click a rock/cloud: inspect/target resource
- Left drag: box-select ships
- Right click ground: move selected ships
- Right click resource with a ship selected: begin auto-harvesting
- `F`: cycle selected fleet move formation
- `R`: toggle miner range overlays on/off
- `ESC`: return to lobby

## Progression loop

1. Select your Prospector.
2. Right-click an iron asteroid to auto-harvest.
3. The ship mines, orbits the asteroid/cloud, returns when full, unloads at the Outpost, then resumes if the node still exists.
4. Repeat for Copper, Silicates, Ice, Hydrogen, and advanced gases as needed.
5. Left-click the Outpost and build a Deployer when the Outpost stockpile can afford it.
6. Move an empty Deployer near the Outpost.
7. Left-click the Outpost and load a Shipyard, Research Lab, or Manufacturing Plant package into the Deployer.
8. Move the loaded Deployer to the desired spot.
9. Left-click the loaded Deployer and place the station. The Deployer is consumed.
10. Use the Shipyard build menu to build industry ships, combat hulls, capitals, titans, and monoliths.
11. Use the Manufacturing Plant build menu to manufacture Fuel from harvested gases.
12. Deliver Fuel to the Research Lab hangar so it stays powered for the later science/research update.
13. Destroyed ships and stations leave individual world loot pieces containing cargo/hangar contents plus Scrap Metal, Hull Plating, and Circuit Fragments.
14. Build a Salvager to tractor those world loot pieces into cargo.

## Ship examples

Early and industry ships:

- Prospector
- Deployer
- Scout
- Hauler
- Deep Miner
- Gas Harvester
- Freighter
- Salvager

Combat and capital classes:

- Frigate
- Destroyer
- Cruiser
- Battle Cruiser
- Battleship
- Carrier
- Dreadnought
- Supercarrier
- Titan
- Monolith

## Station package cost

Shipyard package:

- `500 Iron`
- `250 Copper`
- `350 Silicates`
- `160 Water Ice`

Research Lab package:

- `350 Iron`
- `220 Copper`
- `280 Silicates`
- `120 Water Ice`

Manufacturing Plant package:

- `450 Iron`
- `240 Copper`
- `320 Silicates`
- `140 Water Ice`
- `120 Hydrogen`

## Fuel manufacturing

Fuel recipe:

- Input: `30 Hydrogen`, `10 Helium`, `12 Methane`
- Output: `50 Fuel`
- Crafted at: `Manufacturing Plant`

Research Labs consume `0.25 Fuel` per second from their station hangar while powered.

## Salvage note

World loot pickup requires a ship type with `tractorBeams` and `tractorRange` configured. The default Salvager has no weapon loadout and uses two tractor beams.

## Modding config

The primary rules manifest is:

```text
config/starchem.json
```

That manifest points to separate data files:

```text
config/materials.json
config/stations.json
config/resources.json
config/automation.json
config/craftables/fuel.json
config/ships/early.json
config/ships/industry.json
config/ships/combat-line.json
config/ships/capitals.json
config/ships/megastructures.json
```

`files.ships` in `config/starchem.json` may be either one JSON file or a list of JSON files. The loader merges all ship files in order, so new ship packs can be added without growing one huge config file.

`files.craftables` may also be one JSON file or a list of JSON files. Each craftable item should live in its own JSON file with its required resources, output material, display name, description, style, color, and station types that can craft it.

The Java build loads ships, stations, craftable recipes, resource belt spawning, and resource respawn timing from those files through `Rules.java`, `CraftingRules.java`, and `StationFuelRules.java`. This means ship stats, build costs, station build menus, station package costs, station fuel requirements, craftable recipe costs/outputs, and spawned resource belts can be changed without editing Java source.

Important current limitation: materials are still backed by the Java `Material` enum in `Types.java`, so `materials.json` is currently documentation/metadata for the existing material IDs. A later pass should replace the enum with loaded material definitions if fully custom materials/colors are needed.

Multiplayer note: the host and clients should run the same config files. The host does not yet transmit the full rule set to clients.

## Network design

The current multiplayer model is host-authoritative UDP:

- Host opens the session on a known UDP port.
- Clients send reliable `JOIN|name` messages.
- Host assigns each client a player ID, unique name, color, station, and starter ship.
- Clients send movement, harvest, build, and station commands to the host.
- Host validates the commands and broadcasts snapshots.
- Host syncs ships, ship cargo, resource node positions/amount, stations, world loot, and stockpiles.
- Host also sends periodic reliable full snapshots.
- Reliable messages are wrapped as `REL|messageId|payload`.
- Receivers answer with `ACK|messageId`.
- Unacked reliable messages are resent for a limited number of attempts.

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
