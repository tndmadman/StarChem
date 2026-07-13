# First Game Progression

This is a practical, low-risk progression route for a new v1.1.0-alpha session. Exact priorities can change with system resources, enemy pressure, and multiplayer competition.

## Phase 1: stabilize the starter economy

You begin with a Prospector and Outpost in a protected player-home system. Home systems cannot be captured.

1. Select the Prospector and right-click nearby resource nodes.
2. Prioritize **Iron** and **Copper** first.
3. Build a second Prospector when the Outpost can afford `80 Iron + 40 Copper`.
4. Split workers across metals, minerals, ice, and gases as storage permits.
5. Use `I` to identify which systems and belt types contain materials you are missing.

The Prospector is intentionally progression-safe: it mines both rocks and gases and is built directly from raw resources.

## Phase 2: prepare station expansion

Build a **Deployer** from the Outpost:

- Iron: 220
- Copper: 120
- Silicates: 100
- Ice: 40
- Aluminum: 40

The Deployer carries one station package and is consumed when the station is placed. The Outpost can package a Shipyard, Research Lab, or Manufacturing Plant.

### Recommended first station: Manufacturing Plant

The first Manufacturing Plant deliberately uses only raw resources:

- Iron: 450
- Copper: 240
- Silicates: 320
- Ice: 140
- Hydrogen: 120
- Aluminum: 120
- Nickel: 80
- Carbon: 80

This prevents the economy from deadlocking before manufactured components exist.

## Phase 3: establish foundational manufacturing

Begin with reusable foundation components rather than immediately attempting an advanced hull.

### Recommended first production chain

1. **Fuel** — Hydrogen + Helium + Methane.
2. **Steel Plate** — Iron + Carbon.
3. **Nickel Steel** — Iron + Nickel + Carbon.
4. **Aluminum Alloy** — Aluminum + Copper + Silicates.
5. **Polymer Resin** — Methane + Carbon.
6. **Copper Wiring** — Copper.
7. **Gold Contact Mesh** — Gold + Copper Wiring.
8. **Printed Circuit Board** — Copper Wiring + Silicates + Gold Contact Mesh.
9. **Power Regulator** — Printed Circuit Board + Nickel Steel + Copper Wiring.
10. **Structural Frame** — Steel Plate + Nickel Steel.
11. **Cargo Pod** — Aluminum Alloy + Structural Frame + Polymer Resin.
12. **Fuel Cell Stack** — Fuel + Power Regulator + Nickel Steel.

Keep reserves of raw materials. Research and the Manufacturing Plant’s own replacement cost still consume raw resources.

## Phase 4: build the Research Lab

A Research Lab package requires:

- Structural Frame: 12
- Research Matrix: 4
- Power Regulator: 6
- Water Coolant: 10
- Radiation Shielding: 6

The Research Matrix and Radiation Shielding create a deliberate industrial hurdle. The lab consumes `0.25 Fuel per second` while operating, so build a Fuel reserve before starting research.

## Phase 5: complete Advanced Industry

**Advanced Industry** takes 35 seconds and costs:

- Fuel: 25
- Copper: 120
- Silicates: 100
- Nickel: 90
- Aluminum: 70
- Carbon: 50

It unlocks:

- Deep Miner
- Gas Harvester
- Freighter
- Salvager

This research also unlocks many advanced recipes, including Titanium Alloy, Cobalt Superalloy, Tungsten Carbide, Radiation Shielding, Cryogenic Coolant, Capacitor Banks, Navigation Computers, and specialist industrial assemblies.

### Best specialist choices

- **Deep Miner:** higher-capacity asteroid specialist for metal and mineral belts.
- **Gas Harvester:** specialist for gas clouds and noble gases.
- **Freighter:** 1,440 cargo capacity for large logistics movements.
- **Salvager:** two tractor beams, 360 range, and 600 cargo for battlefield recovery.

## Phase 6: establish the Shipyard

A Shipyard package requires:

- Structural Frame: 20
- Steel Plate: 30
- Power Regulator: 8
- Cargo Pod: 4
- Ice: 80

The Shipyard produces all normal player hulls, but research still controls access to advanced classes.

A Scout is an efficient early exploration hull: speed 275, sensor range 420, and low build time. It requires Aluminum Alloy, Sensor Arrays, a Navigation Computer, a Fuel Cell Stack, and Hydrogen.

## Phase 7: enter combat progression

### Combat Doctrine

Prerequisite: Advanced Industry  
Time: 50 seconds

Cost:

- Fuel: 35
- Steel Plate: 20
- Printed Circuit Board: 8
- Power Regulator: 4
- Circuit Fragments: 20

Unlocks Frigate, Destroyer, and Cruiser. Circuit Fragments are salvage, making combat recovery or reclamation strategically important.

### Battlefleet Engineering

Prerequisite: Combat Doctrine  
Time: 75 seconds

Cost includes Titanium Alloy, Armor Matrices, Fusion Reactors, Targeting Computers, Hull Plating, and Circuit Fragments. It unlocks Battle Cruiser, Battleship, Carrier, and Dreadnought.

### Supercapital Architecture

Prerequisite: Battlefleet Engineering  
Time: 120 seconds

This research requires capital components, rare raw resources, and large salvage reserves. It unlocks Supercarrier, Titan, and Monolith.

## Phase 8: expand into the galaxy

Use the galaxy map to identify systems by role and modifier.

Good early targets:

- **Red Dwarf Foundry:** strong metal production.
- **Gas Giant Frontier:** common and noble gases.
- **Ice Belt:** ice and faster resource respawn.
- **Carbon Basin:** chemical feedstocks.
- **Shattered Worlds:** broad mixed resources and high respawn.

High-risk targets:

- **Warzone:** environmental damage and weak shield regeneration.
- **Volcanic Crucible:** continuous damage and slower movement.
- **Pulsar Reach:** severe radiation with valuable exotic resources.
- **Corsair Den:** hostile strategic territory and reduced sensors.

## Phase 9: capture and defend territory

A capturable system requires at least 3 influence inside its central command zone. A station contributes 4 influence, an armed ship 1.5, an unarmed non-miner 0.75, and a harvesting ship 0.5. An uncontested capture takes 75 seconds.

Control grants:

- 12% mining-yield bonus.
- 8% shield-regeneration bonus.

A station alone can satisfy the capture threshold, but building or holding one inside the command zone exposes it to concentrated attacks. Escort Deployers and reinforce valuable systems.

## Avoiding common progression stalls

- Do not spend every unit of Iron and Copper before building Manufacturing.
- Produce Fuel before starting a long research run.
- Stock intermediate components in batches; late recipes consume earlier products recursively.
- Recover Scrap Metal, Hull Plating, and Circuit Fragments after combat.
- Use reclamation recipes to turn salvage into Steel Plates, Structural Frames, and Printed Circuit Boards.
- Do not send a single-use Deployer into a hazardous system without support.
- Keep multiplayer client and host configurations identical.