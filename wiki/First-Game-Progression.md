# First Game Progression

This page separates what a clean **v1.1.0-alpha** game can currently accomplish from the intended research progression blocked by a tagged-release data issue.

## Critical release issue

A fresh non-developer session cannot enter the configured research tree normally:

1. Advanced Industry can only be researched at a Research Lab.
2. The Research Lab package costs 6 Radiation Shielding.
3. Radiation Shielding requires Advanced Industry.
4. New players begin with neither completed research nor Radiation Shielding.

This circular dependency is confirmed in the tagged configuration and production checks. It is not solved by mining longer. The normal walkthrough therefore reaches a hard stop before Research Lab construction.

Developer free-crafting or a deliberately synchronized configuration change can bypass the blocker for testing, but neither is normal unmodified-release progression. Configuration changes must match on the server and every client.

## Phase 1: stabilize the starter economy

You begin with a Prospector and Outpost in a protected player-home system. Home systems cannot be captured.

1. Select the Prospector and right-click nearby resource nodes.
2. Prioritize **Iron** and **Copper** first.
3. Build a second Prospector when the Outpost can afford `80 Iron + 40 Copper`.
4. Split workers across metals, minerals, ice, and gases as storage permits.
5. Use `I` to identify which systems and belt types contain missing materials.

The Prospector is progression-safe: it harvests both rocks and gases and is built directly from raw resources.

## Phase 2: prepare station expansion

Build a **Deployer** from the Outpost:

- Iron: 220
- Copper: 120
- Silicates: 100
- Ice: 40
- Aluminum: 40

The Deployer carries one station package and is consumed when the station is placed. The Outpost can package a Shipyard, Research Lab, or Manufacturing Plant.

### Recommended first station: Manufacturing Plant

The Manufacturing Plant uses only raw resources:

- Iron: 450
- Copper: 240
- Silicates: 320
- Ice: 140
- Hydrogen: 120
- Aluminum: 120
- Nickel: 80
- Carbon: 80

This correctly allows the manufacturing economy to start before advanced components exist.

## Phase 3: establish foundational manufacturing

Produce reusable foundation components:

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
13. **Water Coolant** — Ice + Nitrogen.
14. **Research Matrix** — Circuit Boards + Sensor Arrays + Radiation Shielding + Gold Contact Mesh + Rare Earths.

The final Research Matrix dependency also reaches Radiation Shielding, reinforcing the same research bootstrap problem.

## Phase 4: practical limit of clean release progression

A Research Lab package requires:

- Structural Frame: 12
- Research Matrix: 4
- Power Regulator: 6
- Water Coolant: 10
- Radiation Shielding: 6

Radiation Shielding is locked behind Advanced Industry, so a normal clean game stops here.

A Shipyard package can still be manufactured from available components:

- Structural Frame: 20
- Steel Plate: 30
- Power Regulator: 8
- Cargo Pod: 4
- Ice: 80

However, many otherwise early-looking hulls remain indirectly blocked by advanced components. For example, the Scout needs a Navigation Computer, and Navigation Computers require Advanced Industry.

## Intended progression after the blocker is fixed or deliberately bypassed

The sections below document the configured intended path. They are not reachable normally in the unmodified tagged release.

## Phase 5: Advanced Industry

Time: 35 seconds.

Cost:

- Fuel: 25
- Copper: 120
- Silicates: 100
- Nickel: 90
- Aluminum: 70
- Carbon: 50

Unlocks:

- Deep Miner
- Gas Harvester
- Freighter
- Salvager

It also unlocks Titanium Alloy, Cobalt Superalloy, Tungsten Carbide, Radiation Shielding, Cryogenic Coolant, Capacitor Banks, Navigation Computers, specialist industrial modules, and several power components.

### Specialist roles

- **Deep Miner:** 220 cargo; asteroid specialist.
- **Gas Harvester:** 180 cargo; gas specialist.
- **Freighter:** 1,440 cargo; heavy logistics.
- **Salvager:** 600 cargo; two tractor beams with 360 range.

## Phase 6: Combat Doctrine

Prerequisite: Advanced Industry  
Time: 50 seconds

Cost:

- Fuel: 35
- Steel Plate: 20
- Printed Circuit Board: 8
- Power Regulator: 4
- Circuit Fragments: 20

Unlocks Frigate, Destroyer, and Cruiser. It also unlocks combat chemicals, reactors, drives, shields, armor, and weapon assemblies.

## Phase 7: Battlefleet Engineering

Prerequisite: Combat Doctrine  
Time: 75 seconds

Cost:

- Fuel 80
- Titanium Alloy 20
- Armor Matrix 10
- Fusion Reactor 2
- Targeting Computer 3
- Hull Plating 45
- Circuit Fragments 60

Unlocks Battle Cruiser, Battleship, Carrier, and Dreadnought.

## Phase 8: Supercapital Architecture

Prerequisite: Battlefleet Engineering  
Time: 120 seconds

This research requires capital components, rare raw materials, and large salvage reserves. It unlocks Supercarrier, Titan, and Monolith.

## Galaxy expansion

Use the galaxy map to identify systems by role and modifier.

Good economic targets:

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

## Territory capture

A capturable system requires at least 3 influence inside its central command zone:

- Station: 4.0
- Armed ship: 1.5
- Unarmed non-miner: 0.75
- Harvest-capable ship: 0.5

An uncontested capture takes 75 seconds.

Control grants:

- 12% mining-yield bonus.
- 8% shield-regeneration bonus.

A station alone satisfies the threshold, but building or holding one inside the command zone exposes it to concentrated attacks.

## Avoiding other progression stalls

- Do not spend every unit of Iron and Copper before Manufacturing.
- Stock foundational components in batches.
- Recover Scrap Metal, Hull Plating, and Circuit Fragments after combat.
- Use reclamation recipes to recover Steel Plates, Structural Frames, and Circuit Boards.
- Route required resources into the producing station’s own hangar.
- Escort single-use Deployers in contested or hazardous systems.
- Keep multiplayer client and host configurations identical.
- Do not mistake the Research Lab circular dependency for ordinary missing resources; it is a release blocker.