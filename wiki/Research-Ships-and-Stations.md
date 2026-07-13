# Research, Ships, and Stations

This page documents the configuration shipped in **v1.1.0-alpha**.

## Important known progression blocker

A fresh non-developer game contains a circular bootstrap dependency:

1. **Advanced Industry** can only be researched at a Research Lab.
2. A Research Lab package costs 6 Radiation Shielding.
3. The Radiation Shielding recipe requires Advanced Industry.
4. New players start with no completed research or stored Radiation Shielding.

Therefore, the configured research path cannot be started normally from a clean v1.1.0-alpha game unless Radiation Shielding is obtained through an external/nonstandard route or developer free-crafting is deliberately enabled. Editing the configuration is not multiplayer-safe unless the host and every client use the same edited files.

The tables below still document the intended release tree and all configured values.

## Research tree

### Advanced Industry

- Prerequisites: none
- Station: Research Lab
- Time: 35 seconds
- Cost: Fuel 25, Copper 120, Silicates 100, Nickel 90, Aluminum 70, Carbon 50
- Unlocks: Deep Miner, Gas Harvester, Freighter, Salvager
- Also gates many advanced alloy, coolant, electronics, industrial, propulsion, and shielding recipes.

### Combat Doctrine

- Prerequisite: Advanced Industry
- Station: Research Lab
- Time: 50 seconds
- Cost: Fuel 35, Steel Plate 20, Printed Circuit Board 8, Power Regulator 4, Circuit Fragments 20
- Unlocks: Frigate, Destroyer, Cruiser
- Also gates combat chemicals, reactors, drives, shields, armor, and weapon assemblies.

### Battlefleet Engineering

- Prerequisite: Combat Doctrine
- Station: Research Lab
- Time: 75 seconds
- Cost: Fuel 80, Titanium Alloy 20, Armor Matrix 10, Fusion Reactor 2, Targeting Computer 3, Hull Plating 45, Circuit Fragments 60
- Unlocks: Battle Cruiser, Battleship, Carrier, Dreadnought
- Also gates fighter-control, lance, and capital-core production.

### Supercapital Architecture

- Prerequisite: Battlefleet Engineering
- Station: Research Lab
- Time: 120 seconds
- Cost: Fuel 140, Capital Reactor Core 4, Command Core 4, Shield Generator 8, Fighter Bay Module 2, Platinum 120, Uranium 80, Xenon 60, Scrap Metal 120, Hull Plating 120, Circuit Fragments 140
- Unlocks: Supercarrier, Titan, Monolith
- Gates Megastructure Truss production.

## Stations

| Station | HP | Shield | Regen | Build time | Primary function |
|---|---:|---:|---:|---:|---|
| Outpost | 1,200 | 800 | 12/s | Starting station | Starter ships and station packages |
| Shipyard | 2,400 | 1,800 | 20/s | 45s | All player ship construction |
| Research Lab | 1,600 | 1,100 | 14/s | 35s | Research; consumes Fuel |
| Manufacturing Plant | 2,000 | 1,400 | 16/s | 40s | All intermediate recipes |

### Outpost

Builds:

- Prospector
- Deployer

Packages:

- Shipyard
- Research Lab
- Manufacturing Plant

### Shipyard package

Cost:

- Structural Frame 20
- Steel Plate 30
- Power Regulator 8
- Cargo Pod 4
- Ice 80

The Shipyard can build every normal player hull, subject to research and resource requirements.

### Research Lab package

Cost:

- Structural Frame 12
- Research Matrix 4
- Power Regulator 6
- Water Coolant 10
- Radiation Shielding 6

Fuel consumption: `0.25 Fuel/second` while operating.

See the bootstrap blocker at the top of this page.

### Manufacturing Plant package

Raw-resource cost:

- Iron 450
- Copper 240
- Silicates 320
- Ice 140
- Hydrogen 120
- Aluminum 120
- Nickel 80
- Carbon 80

This station is deliberately buildable from raw resources so manufacturing can start without manufactured components.

### Package placement

Station packages occupy one Deployer package slot. A Deployer is removed after placing its station. Packages cannot be placed without a loaded Deployer.

## Starter and utility ships

| Ship | HP/Shield | Speed | Cargo | Build | Cost | Gate |
|---|---:|---:|---:|---:|---|---|
| Prospector | 100/45 | 185 | 120 | 8s | Iron 80, Copper 40 | None |
| Deployer | 240/140 | 115 | 0 | 14s | Iron 220, Copper 120, Silicates 100, Ice 40, Aluminum 40 | None |
| Scout | 70/50 | 275 | 45 | 6s | Aluminum Alloy 10, Sensor Array 2, Navigation Computer 1, Fuel Cell Stack 1, Hydrogen 20 | No ship research gate; components require industry |
| Hauler | 150/0 | 138 | 340 | 10s | Structural Frame 8, Cargo Pod 6, Logistics Module 1, Fuel Cell Stack 2, Carbon 20, PDL Assembly 1 | No ship research gate; components require research |

### Prospector

- Starter miner.
- Harvests silicate rocks and gas clouds.
- Harvest range: 105.
- Scout range: 320.
- Direct raw-resource construction prevents initial mining deadlock.

### Deployer

- Carries one station package.
- Can carry a Shipyard package according to the ship definition; station package rules also require this ship type for packaged stations.
- Single-use after placement.

### Scout

- Fastest normal early player hull at speed 275.
- Scout range: 420.
- Dispatch limit: 5.
- No harvesting capability.

### Hauler

- General cargo ship with 340 capacity.
- No harvesting capability.
- Includes a point-defense component in its construction cost.

## Advanced industry ships

Requires **Advanced Industry**.

| Ship | HP/Shield | Speed | Cargo | Build | Cost |
|---|---:|---:|---:|---:|---|
| Deep Miner | 180/0 | 125 | 220 | 14s | Frame 10, Mining Head 4, Power Regulator 2, Ion Thruster 1, Titanium 20 |
| Gas Harvester | 125/0 | 150 | 180 | 14s | Pressure Hull 8, Gas Compressor 4, Cryogenic Coolant 10, Ion Thruster 1, Navigation Computer 1 |
| Freighter | 360/0 | 92 | 1,440 | 24s | Frame 25, Cargo Pod 20, Logistics Module 4, Ion Thruster 4, Fuel Cell Stack 4, PDL Assembly 2 |
| Salvager | 135/115 | 135 | 600 | 16s | Frame 10, Tractor Emitter 3, Cargo Pod 8, Sensor Array 2, Ion Thruster 1 |

- Deep Miner harvests only silicate-rock nodes; range 125.
- Gas Harvester harvests only gas-cloud nodes; range 130.
- Freighter is the primary heavy cargo hull.
- Salvager has two tractor beams with 360 range.

## Combat Doctrine hulls

Requires **Combat Doctrine**.

| Ship | HP/Shield | Regen | Speed | Cargo | Build | Cost |
|---|---:|---:|---:|---:|---:|---|
| Frigate | 180/140 | 8/s | 230 | 35 | 12s | Frame 8, Fuel Cell 2, Shield Emitter 2, Railgun 1, Nickel 20 |
| Destroyer | 280/220 | 9/s | 190 | 55 | 18s | Frame 14, Fuel Cell 3, Shield Emitter 3, Railgun 2, Guidance 1, Warhead 2, PDL 1 |
| Cruiser | 520/420 | 12/s | 150 | 90 | 28s | Frame 25, Titanium Alloy 15, Fusion Reactor 2, Fusion Drive 2, Shield Generator 2, Railgun 1, Heavy Cannon 1, Torpedo 1 |

## Battlefleet hulls

Requires **Battlefleet Engineering**.

| Ship | HP/Shield | Regen | Speed | Cargo | Build | Cost |
|---|---:|---:|---:|---:|---:|---|
| Battle Cruiser | 850/720 | 15/s | 128 | 130 | 40s | Frame 40, Titanium Alloy 25, Fusion Reactor 3, Fusion Drive 3, Shield Generator 3, Armor Matrix 8, Heavy Cannon 2, Torpedo 2, Targeting Computer 2 |
| Battleship | 1,400/1,200 | 18/s | 96 | 190 | 55s | Frame 70, Titanium Alloy 50, Fusion Reactor 5, Fusion Drive 4, Shield Generator 5, Armor Matrix 20, Heavy Cannon 4, Torpedo 4, Targeting Computer 4, Platinum 20, PDL 2 |
| Carrier | 2,600/2,400 | 28/s | 72 | 480 | 75s | Frame 150, Armor Matrix 40, Capital Reactor 2, Capital Drive 2, Command Core 2, Fighter Bay 4, Shield Generator 10, Fighter Control 6, Platinum 50, PDL 2 |
| Dreadnought | 3,400/3,200 | 24/s | 58 | 380 | 90s | Frame 180, Armor Matrix 70, Capital Reactor 3, Capital Drive 2, Command Core 1, Lance Array 2, Torpedo 4, Shield Generator 8, Uranium 60 |

## Supercapital and megastructure hulls

Requires **Supercapital Architecture**.

| Ship | HP/Shield | Regen | Speed | Cargo | Build |
|---|---:|---:|---:|---:|---:|
| Supercarrier | 6,200/6,500 | 45/s | 42 | 900 | 130s |
| Titan | 12,000/14,000 | 70/s | 30 | 1,800 | 180s |
| Monolith | 32,000/0 | 0 | 12 | 6,000 | 240s |

### Supercarrier cost

- Megastructure Truss 20
- Capital Reactor Core 6
- Capital Drive Core 5
- Command Core 4
- Fighter Bay Module 12
- Shield Generator 20
- Armor Matrix 120
- Platinum 180
- Point-Defense Laser Assembly 2

### Titan cost

- Megastructure Truss 50
- Capital Reactor Core 12
- Capital Drive Core 8
- Command Core 8
- Shield Generator 35
- Armor Matrix 250
- Lance Focusing Array 8
- Heavy Cannon Assembly 8
- Uranium 300
- Xenon 250
- Neon 120
- Point-Defense Laser Assembly 2

### Monolith cost

- Megastructure Truss 180
- Capital Reactor Core 30
- Capital Drive Core 16
- Command Core 20
- Shield Generator 80
- Armor Matrix 600
- Manufacturing Line 30
- Research Matrix 30
- Uranium 1,200
- Xenon 1,000
- Neon 700
- Gold 1,200
- Point-Defense Laser Assembly 3

The Monolith is a mobile megastructure hull with extremely high health and cargo capacity, but no configured shield and a speed of only 12.

## Automated-only shuttles

These hulls are internal logistics units and cannot be crafted directly:

| Shuttle | HP/Shield | Speed | Cargo | Purpose |
|---|---:|---:|---:|---|
| Fuel Shuttle | 60/20 | 245 | 50 | Automated Fuel courier |
| Logistics Shuttle | 60/20 | 245 | 50 | Automated station-material courier |

Both are marked `automatedOnly: true` and `craftable: false`.