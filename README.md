# StarChem

StarChem is a Java 2D top-down multiplayer RTS prototype.

It is written in plain Java/Swing with a UDP host/client layer. The game opens into an in-game lobby, then swaps into the RTS view when you choose Solo, Host, or Join.

## Playable right now

- In-game lobby
- Solo, host, and join flow
- One starter Prospector per player
- Default Outpost station per player
- Ship inventory and station stockpile
- Right-click resource auto-harvesting
- Full cargo return-to-station behavior
- Automatic unloading near station
- Idle ships orbit/wander near station
- Harvesting ships orbit the asteroid/cloud while working
- Depleted resource nodes vanish and respawn somewhere else
- Outposts build Prospectors and Deployers
- Outposts fabricate/load Shipyard packages into Deployers
- Deployers place Shipyards and are consumed
- Shipyards unlock Haulers and Scouts
- Movement route line, destination ring, speed, and ETA
- Automatic fleet camera/zoom
- UDP host/client synchronization for players, ships, stations, cargo, resources, and stockpiles

## Controls

- Left click your ship: select ship
- Left click a rock/cloud: inspect/target resource
- Left drag: box-select ships
- Right click ground: move selected ships
- Right click resource with a ship selected: begin auto-harvesting
- `1`: build Prospector
- `2`: build Deployer
- `3`: build Hauler, requires Shipyard
- `4`: build Scout, requires Shipyard
- `U` with selected empty Deployer near Outpost: load Shipyard package if you can afford it
- `U` with selected loaded Deployer: place Shipyard and consume Deployer
- `ESC`: return to lobby

## Progression loop

1. Select your Prospector.
2. Right-click an iron asteroid to auto-harvest.
3. The ship mines, orbits the asteroid/cloud, returns when full, unloads at the Outpost, then resumes if the node still exists.
4. Repeat for Copper, Silicates, and Ice as needed.
5. Press `2` to build a Deployer when the Outpost stockpile can afford it.
6. Select the Deployer near the Outpost and press `U` to fabricate/load a Shipyard package.
7. Move the loaded Deployer to the desired spot.
8. Press `U` again to place the Shipyard. The Deployer is consumed.
9. Use the Shipyard to build Haulers with `3` and Scouts with `4`.

## Ship costs

- Prospector: `80 Iron + 40 Copper`
- Deployer: `220 Iron + 120 Copper + 100 Silicates + 40 Water Ice`
- Hauler: `150 Iron + 60 Copper + 80 Silicates`
- Scout: `60 Iron + 90 Copper + 40 Hydrogen`

## Station package cost

Shipyard package:

- `500 Iron`
- `250 Copper`
- `350 Silicates`
- `160 Water Ice`

## Rules config

The rules file is:

```text
config/starchem-rules.json
```

It describes the intended moddable rule data for materials, ship types, station types, costs, ship traits, station traits, automation behavior, and resource respawn behavior.

Important: the current Java build now mirrors these rules in code, but it still does not fully load and sync the JSON as the match authority. The next architecture pass should make the host load `config/starchem-rules.json`, send it to clients, and use that loaded config directly instead of the Java defaults.

## Network design

The current multiplayer model is host-authoritative UDP:

- Host opens the session on a known UDP port.
- Clients send reliable `JOIN|name` messages.
- Host assigns each client a player ID, unique name, color, station, and starter ship.
- Clients send movement, harvest, build, and station commands to the host.
- Host validates the commands and broadcasts snapshots.
- Host syncs ships, ship cargo, resource node positions/amounts, stations, and stockpiles.
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

Host directly:

```bash
gradle run --args="--host 50000 --name Host"
```

Client directly:

```bash
gradle run --args="--join 127.0.0.1 50000 --name Player"
```

## Next build steps

- [ ] Make `config/starchem-rules.json` the actual source of truth.
- [ ] Rename either `station_builder` or `builder` so JSON and Java match.
- [ ] Add snapshot sequence rejection on the client.
- [ ] Replace the raw delimited UDP protocol with JSON or length-prefixed packets.
- [ ] Update README controls.
- [ ] Add a Gradle wrapper and a tiny CI build.
- [ ] Add tests for `CargoCodec`, `SnapshotReader`/`SnapshotWriter`, `CommandAuth`, and `BuildSystem`.
- [ ] Add build queues/timers instead of instant construction.
- [ ] Add reconnect support.
- [ ] Add fog of war.
- [ ] Add combat/projectiles.
- [ ] Add NAT traversal or relay fallback for internet play.
