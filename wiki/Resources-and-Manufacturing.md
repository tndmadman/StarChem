# Resources and Manufacturing

StarChem v1.1.0-alpha uses a JSON-driven economy with 24 raw mineable materials, three salvage materials, Fuel, and 56 manufactured intermediate materials. The Manufacturing Plant loads 60 recipes across nine recipe files.

## Material types

- **Raw:** naturally appears in configured resource belts.
- **Refined:** Fuel made from gases.
- **Salvage:** recovered from destroyed ships and stations.
- **Manufactured:** produced by a Manufacturing Plant.

Use `I` in game to inspect the loaded material catalog and identify systems and node types where raw materials can appear.

## Raw materials

### Metals

| Material | Tier | Strong sources | Common uses |
|---|---|---|---|
| Iron | Common | Sol, Red Dwarf, Binary Forge, Empty Frontier | Steel, starter ships, first Manufacturing Plant |
| Copper | Common | Sol, Red Dwarf, Empty Frontier | Wiring, electronics, starter ships |
| Nickel | Common | Red Dwarf, Binary Forge | Nickel Steel, alloys, early research |
| Cobalt | Uncommon | Red Dwarf, Binary Forge, Volcanic Crucible | Superalloys, reactors, drives |
| Aluminum | Common | Sol, Binary Forge, Empty Frontier | Light alloys, scouts, cargo systems |
| Titanium | Uncommon | Red Dwarf, Binary Forge, Volcanic, Pulsar | Advanced hulls, drives, armor |
| Tungsten | Rare | Red Dwarf, Binary Forge, Volcanic, Pulsar | Carbide, armor, weapons, shielding |
| Gold | Rare | Corsair Den, Shattered Worlds, Ancient Graveyard | Precision contacts, command systems |
| Platinum | Rare | Warzone, Corsair Den, Pulsar, Ancient Graveyard | Capital reactors, lances, supercapitals |
| Uranium | Exotic | Warzone, Corsair Den, Volcanic, Pulsar | Fission and capital systems |

### Minerals and volatiles

| Material | Family | Tier | Strong sources | Common uses |
|---|---|---|---|---|
| Silicates | Mineral | Common | Sol, Empty Frontier, Shattered Worlds | Ceramics, electronics, stations |
| Water Ice | Volatile | Common | Ice Belt, Sol, Empty Frontier | Coolants, stations, research infrastructure |
| Carbon | Mineral | Common | Carbon Basin, Shattered Worlds | Steel, polymers, chemicals |
| Sulfur | Mineral | Uncommon | Volcanic Crucible, Carbon Basin | Lubricants and explosives |
| Phosphates | Mineral | Uncommon | Carbon Basin, Ice Belt | Ceramics and shielding |
| Rare Earths | Mineral | Rare | Pulsar, Ancient Graveyard, Shattered Worlds | Sensors, shields, capital electronics |

### Gases

| Material | Tier | Strong sources | Common uses |
|---|---|---|---|
| Hydrogen | Common | Gas Giant, Nebula, Ice Belt | Fuel, fusion, propellant |
| Helium | Common | Gas Giant, Nebula, Ice Belt | Fuel, fusion, shield plasma |
| Methane | Common | Carbon Basin, Gas Giant, Nebula | Fuel, polymers, propellant |
| Ammonia | Uncommon | Carbon Basin, Corsair Den, Warzone | Explosives and hypergolic propellant |
| Nitrogen | Uncommon | Carbon Basin, Nebula, Ice Belt | Coolants and propellant |
| Neon | Rare | Gas Giant, Nebula, Pulsar, Ancient Graveyard | Shield plasma and supercapitals |
| Argon | Rare | Gas Giant, Nebula, Pulsar, Ancient Graveyard | Sensors, shield plasma, point defense |
| Xenon | Exotic | Pulsar, Ancient Graveyard, Gas Giant | Ion drives, capital reactors, supercapitals |

## Salvage materials

| Material | Source and purpose |
|---|---|
| Scrap Metal | Reclaimed into Steel Plate; also required by Supercapital Architecture |
| Hull Plating | Rebuilt into Structural Frames; required by late research and armor |
| Circuit Fragments | Reprocessed into Printed Circuit Boards; required by combat and capital research |

Salvage supports an alternate industrial route and makes battlefield recovery economically important.

## Recipe notation

Each table shows:

- **Time:** base production time in seconds.
- **Inputs:** resources consumed per run.
- **Output:** material and quantity produced.
- **Gate:** required research, if any.

All recipes run at a Manufacturing Plant.

## Fuel — 1 recipe

| Recipe | Time | Inputs | Output | Gate |
|---|---:|---|---|---|
| Fuel | 12 | Hydrogen 30, Helium 10, Methane 12 | Fuel 50 | None |

Fuel powers Research Labs at 0.25 units per second and is consumed by research and power-system recipes.

## Processed materials — 8 recipes

| Recipe | Time | Inputs | Output | Gate |
|---|---:|---|---|---|
| Steel Plate | 8 | Iron 30, Carbon 4 | Steel Plate 20 | None |
| Nickel Steel | 9 | Iron 24, Nickel 10, Carbon 4 | Nickel Steel 16 | None |
| Aluminum Alloy | 9 | Aluminum 24, Copper 6, Silicates 4 | Aluminum Alloy 18 | None |
| Titanium Alloy | 12 | Titanium 16, Aluminum 8, Nickel 6 | Titanium Alloy 12 | Advanced Industry |
| Cobalt Superalloy | 13 | Cobalt 10, Nickel 8, Carbon 4 | Cobalt Superalloy 10 | Advanced Industry |
| Tungsten Carbide | 14 | Tungsten 12, Carbon 8 | Tungsten Carbide 10 | Advanced Industry |
| Ceramic Composite | 10 | Silicates 24, Carbon 6, Phosphates 4 | Ceramic Composite 16 | None |
| Radiation Shielding | 15 | Tungsten 8, Silicates 10, Phosphates 6 | Radiation Shielding 10 | Advanced Industry |

## Chemicals — 8 recipes

| Recipe | Time | Inputs | Output | Gate |
|---|---:|---|---|---|
| Polymer Resin | 8 | Methane 18, Carbon 8 | Polymer Resin 20 | None |
| Industrial Lubricant | 7 | Methane 12, Sulfur 6 | Industrial Lubricant 20 | None |
| Water Coolant | 8 | Ice 30, Nitrogen 5 | Water Coolant 25 | None |
| Cryogenic Coolant | 11 | Ice 24, Nitrogen 8, Argon 3 | Cryogenic Coolant 18 | Advanced Industry |
| Methane Propellant | 9 | Methane 20, Hydrogen 10 | Methane Propellant 20 | None |
| Hypergolic Propellant | 12 | Ammonia 16, Nitrogen 10, Hydrogen 8 | Hypergolic Propellant 16 | Combat Doctrine |
| Explosive Compound | 11 | Ammonia 12, Sulfur 8, Carbon 6 | Explosive Compound 14 | Combat Doctrine |
| Shield Plasma Mix | 14 | Helium 14, Neon 4, Argon 6 | Shield Plasma Mix 12 | Combat Doctrine |

## Electronics — 8 recipes

| Recipe | Time | Inputs | Output | Gate |
|---|---:|---|---|---|
| Copper Wiring | 6 | Copper 20 | Copper Wiring 25 | None |
| Gold Contact Mesh | 8 | Gold 6, Copper Wiring 10 | Gold Contact Mesh 12 | None |
| Printed Circuit Board | 9 | Copper Wiring 12, Silicates 8, Gold Contact Mesh 2 | Printed Circuit Board 10 | None |
| Power Regulator | 10 | Printed Circuit Board 4, Nickel Steel 4, Copper Wiring 6 | Power Regulator 8 | None |
| Capacitor Bank | 11 | Printed Circuit Board 4, Aluminum Alloy 6, Gold Contact Mesh 2 | Capacitor Bank 8 | Advanced Industry |
| Sensor Array | 11 | Printed Circuit Board 4, Rare Earths 4, Argon 3 | Sensor Array 6 | None |
| Navigation Computer | 12 | Printed Circuit Board 3, Sensor Array 2, Gold Contact Mesh 1 | Navigation Computer 4 | Advanced Industry |
| Targeting Computer | 14 | Navigation Computer 1, Sensor Array 2, Circuit Fragments 4 | Targeting Computer 4 | Combat Doctrine |

## Industrial assemblies — 8 recipes

| Recipe | Time | Inputs | Output | Gate |
|---|---:|---|---|---|
| Structural Frame | 10 | Steel Plate 12, Nickel Steel 6 | Structural Frame 10 | None |
| Pressure Hull Section | 12 | Structural Frame 4, Ceramic Composite 6, Polymer Resin 4 | Pressure Hull Section 8 | Advanced Industry |
| Cargo Pod | 10 | Aluminum Alloy 8, Structural Frame 4, Polymer Resin 4 | Cargo Pod 10 | None |
| Mining Head | 14 | Tungsten Carbide 6, Titanium Alloy 4, Power Regulator 2 | Mining Head 4 | Advanced Industry |
| Gas Compressor | 15 | Pressure Hull Section 3, Cobalt Superalloy 4, Cryogenic Coolant 4 | Gas Compressor 4 | Advanced Industry |
| Tractor Beam Emitter | 15 | Capacitor Bank 3, Cobalt Superalloy 3, Rare Earths 4 | Tractor Beam Emitter 4 | Advanced Industry |
| Fabrication Toolset | 14 | Titanium Alloy 4, Tungsten Carbide 4, Power Regulator 2 | Fabrication Toolset 4 | Advanced Industry |
| Logistics Control Module | 14 | Navigation Computer 2, Sensor Array 2, Cargo Pod 4 | Logistics Control Module 4 | Advanced Industry |

## Power and defense — 8 recipes

| Recipe | Time | Inputs | Output | Gate |
|---|---:|---|---|---|
| Fuel Cell Stack | 12 | Fuel 30, Power Regulator 2, Nickel Steel 4 | Fuel Cell Stack 6 | None |
| Reactor Control Unit | 13 | Power Regulator 3, Printed Circuit Board 3, Gold Contact Mesh 2 | Reactor Control Unit 4 | Advanced Industry |
| Fission Reactor Core | 20 | Uranium 12, Radiation Shielding 6, Reactor Control Unit 2 | Fission Reactor Core 2 | Combat Doctrine |
| Fusion Reactor | 22 | Hydrogen 30, Helium 15, Cobalt Superalloy 8, Reactor Control Unit 2 | Fusion Reactor 2 | Combat Doctrine |
| Ion Thruster | 16 | Xenon 6, Capacitor Bank 4, Titanium Alloy 6 | Ion Thruster 4 | Advanced Industry |
| Fusion Drive | 24 | Fusion Reactor 1, Methane Propellant 10, Cobalt Superalloy 6 | Fusion Drive 2 | Combat Doctrine |
| Shield Emitter | 16 | Capacitor Bank 4, Shield Plasma Mix 4, Rare Earths 4 | Shield Emitter 6 | Combat Doctrine |
| Armor Matrix | 20 | Titanium Alloy 8, Tungsten Carbide 6, Ceramic Composite 8, Hull Plating 4 | Armor Matrix 8 | Combat Doctrine |

## Weapons — 8 recipes

| Recipe | Time | Inputs | Output | Gate |
|---|---:|---|---|---|
| Point-Defense Laser Assembly | 14 | Gold Contact Mesh 2, Capacitor Bank 3, Argon 4 | PDL Assembly 4 | Combat Doctrine |
| Railgun Assembly | 16 | Tungsten Carbide 5, Cobalt Superalloy 4, Capacitor Bank 3 | Railgun Assembly 4 | Combat Doctrine |
| Heavy Cannon Assembly | 18 | Steel Plate 8, Tungsten Carbide 5, Explosive Compound 5 | Heavy Cannon Assembly 3 | Combat Doctrine |
| Missile Guidance Package | 16 | Targeting Computer 2, Sensor Array 2, Copper Wiring 5 | Guidance Package 5 | Combat Doctrine |
| Missile Warhead | 14 | Explosive Compound 6, Tungsten Carbide 3 | Missile Warhead 8 | Combat Doctrine |
| Torpedo Assembly | 22 | Guidance Package 2, Warhead 4, Hypergolic Propellant 6, Titanium Alloy 4 | Torpedo Assembly 4 | Combat Doctrine |
| Fighter Control Module | 22 | Navigation Computer 2, Targeting Computer 2, Logistics Module 2 | Fighter Control Module 3 | Battlefleet Engineering |
| Lance Focusing Array | 26 | Platinum 8, Gold Contact Mesh 4, Shield Plasma Mix 6, Capacitor Bank 6 | Lance Array 2 | Battlefleet Engineering |

## Capital systems — 8 recipes

| Recipe | Time | Inputs | Output | Gate |
|---|---:|---|---|---|
| Shield Generator | 22 | Shield Emitter 4, Reactor Control Unit 2, Cryogenic Coolant 6 | Shield Generator 3 | Combat Doctrine |
| Capital Reactor Core | 32 | Fission Core 2, Fusion Reactor 2, Platinum 10, Xenon 10 | Capital Reactor Core 2 | Battlefleet Engineering |
| Capital Drive Core | 34 | Fusion Drive 3, Ion Thruster 4, Capital Reactor Core 1, Rare Earths 8 | Capital Drive Core 2 | Battlefleet Engineering |
| Command Core | 30 | Navigation Computer 3, Targeting Computer 3, Sensor Array 4, Platinum 8, Circuit Fragments 10 | Command Core 2 | Battlefleet Engineering |
| Fighter Bay Module | 34 | Structural Frame 12, Cargo Pod 8, Fighter Control Module 3, Shield Generator 2 | Fighter Bay Module 2 | Battlefleet Engineering |
| Manufacturing Line | 26 | Fabrication Toolset 4, Structural Frame 10, Power Regulator 6, Cargo Pod 4 | Manufacturing Line 2 | Advanced Industry |
| Research Matrix | 24 | Printed Circuit Board 8, Sensor Array 4, Radiation Shielding 4, Gold Contact Mesh 4, Rare Earths 8 | Research Matrix 2 | None |
| Megastructure Truss | 40 | Structural Frame 20, Titanium Alloy 16, Tungsten Carbide 12, Armor Matrix 10, Hull Plating 8 | Megastructure Truss 4 | Supercapital Architecture |

## Reclamation — 3 recipes

| Recipe | Time | Inputs | Output | Gate |
|---|---:|---|---|---|
| Reclaimed Steel Plate | 8 | Scrap Metal 18, Industrial Lubricant 2 | Steel Plate 10 | None |
| Reclaimed Structural Frame | 10 | Hull Plating 10, Steel Plate 4 | Structural Frame 5 | Advanced Industry |
| Reclaimed Circuit Board | 9 | Circuit Fragments 8, Copper Wiring 4, Gold Contact Mesh 1 | Printed Circuit Board 4 | None |

## Dependency strategy

### Foundation stockpile

Keep recurring reserves of:

- Steel Plate
- Nickel Steel
- Aluminum Alloy
- Copper Wiring
- Gold Contact Mesh
- Printed Circuit Board
- Power Regulator
- Structural Frame
- Polymer Resin
- Fuel

These components sit near the bottom of many dependency chains.

### Advanced-industry stockpile

After Advanced Industry, add:

- Titanium Alloy
- Cobalt Superalloy
- Tungsten Carbide
- Capacitor Bank
- Navigation Computer
- Ion Thruster
- Radiation Shielding
- Cryogenic Coolant

### Combat stockpile

After Combat Doctrine, maintain:

- Targeting Computers
- Fusion Reactors and Drives
- Shield Emitters and Generators
- Armor Matrices
- Railgun, cannon, missile, and torpedo components

### Capital stockpile

Capital construction is constrained by both component depth and rare materials. Build Capital Reactor Cores, Capital Drive Cores, Command Cores, Shield Generators, Fighter Bay Modules, and Megastructure Trusses before committing to a hull.

## Diagnosing a stalled build

When a ship, station, recipe, or research item cannot start:

1. Confirm required research is complete.
2. Read every direct missing component.
3. Trace each missing manufactured component backward to its recipe.
4. Check for salvage-only requirements such as Hull Plating or Circuit Fragments.
5. Check Fuel reserves and Research Lab fuel consumption.
6. Confirm the materials are stored where the station can access them.
7. Use the resource catalog to locate raw inputs in other systems.

The v1.1.0-alpha player production interface does not automatically create missing subcomponents. Plan batches from the bottom of the dependency tree upward.