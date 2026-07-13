# Galaxy and Territory

## Galaxy structure

A host creates a persistent galaxy with one or two copies of every registered static system template. The release contains 14 templates. Protected player-home systems are separate from shared capturable territory.

The galaxy always maintains a connected permanent structure. Additional seeded shortcuts are created as wandering-wormhole pairs.

Default `config/galaxy.json`:

```json
{
  "topology": {
    "wanderingWormholePairs": 4
  }
}
```

Accepted values are 0 through 32. A value of 0 keeps only the permanent topology. The same galaxy seed and setting create the same extra links. The host reads this setting when a session is created, so changing it requires a new session.

## System modifier meanings

System modifiers multiply or add to normal game behavior:

- `miningYield`: resource output multiplier.
- `resourceRespawn`: resource replacement-rate multiplier.
- `sensorRange`: sensor-range multiplier.
- `shieldRegen`: shield-regeneration multiplier.
- `movementSpeed`: movement-speed multiplier.
- `weaponRange`: weapon-range multiplier.
- `environmentalDamagePerSecond`: continuous environmental damage.

Territory-control bonuses stack with the system’s environmental modifiers.

## System quick reference

| System | Role | Important modifiers | Strategic identity |
|---|---|---|---|
| Sol Standard | standard | Mining 1.00, respawn 1.00 | Balanced metals, minerals, ice, and fuel gases |
| Red Dwarf Foundry | industrial | Mining 1.14, respawn 0.95 | Strong common and advanced metal production |
| Gas Giant Frontier | gas | Mining 1.12, sensors 0.92 | Large common-gas and noble-gas fields |
| Ice Belt | ice | Respawn 1.12, movement 0.96 | Ice, cold gases, and fast resource renewal |
| Warzone | danger | Weapon range 1.08, shield regen 0.90, damage 0.35/s | Valuable mixed resources under constant danger |
| Corsair Den | corsair home | Sensors 0.86, weapon range 1.04 | High-value metals and hostile strategic pressure |
| Empty Frontier | player home | Respawn 1.08 | Protected and progression-complete starter space |
| Binary Forge | industrial | Mining 1.18, respawn 0.92, shield regen 0.95 | Best raw mining multiplier and heavy metals |
| Volcanic Crucible | hazard | Mining 1.12, movement 0.94, damage 0.80/s | Sulfur and refractory metals in severe heat |
| Nebula Expanse | gas | Mining 1.08, sensors 0.72, weapon range 0.90 | Huge gas fields with major visibility suppression |
| Shattered Worlds | salvage | Respawn 1.15, movement 0.97 | Broad mixed resources and highest respawn multiplier |
| Pulsar Reach | strategic | Sensors 1.20, shield regen 0.72, damage 1.15/s | Exotic resources under the release’s harshest radiation |
| Carbon Basin | chemical | Mining 1.06, respawn 1.12 | Carbon, phosphates, methane, ammonia, and chemical feedstocks |
| Ancient Graveyard | relic | Sensors 0.88, respawn 0.82, weapon range 1.06 | Rare metals and noble gases with slow renewal |

## Detailed system guide

### Sol Standard

Tags: starter, balanced, civilized.

- Inner Industrial Belt: Iron, Copper, Nickel, Aluminum.
- Silicate Carbon Belt: Silicates, Carbon, Phosphates.
- Outer Ice Ring: Water Ice and Silicates.
- Fuel Gas Band: Hydrogen, Helium, Methane, Ammonia, Nitrogen.

Use Sol Standard as a balanced learning environment or general-purpose production base.

### Red Dwarf Foundry

Tags: industrial, metal-rich, hot.

- Foundry Ring: Iron, Nickel, Copper, Aluminum.
- Forge Shards: Silicates, Cobalt, Titanium, Tungsten.

The 14% mining multiplier makes this an efficient industrial center. Resource respawn is slightly slower than normal.

### Gas Giant Frontier

Tags: gas-rich, frontier, fuel-feedstock.

- Fuel Gas Sea: Hydrogen, Helium, Methane, Ammonia, Nitrogen.
- Noble Gas Wake: Argon, Neon, Xenon, Helium.
- Anchor Rocks: Iron, Silicates, Nickel, Carbon.

This is one of the best systems for Fuel, cryogenic production, ion propulsion, shields, and advanced noble-gas components.

### Ice Belt

Tags: ice-rich, frontier, volatile.

- Frozen Shelf: Ice, Silicates, Carbon, Phosphates.
- Cold Gas Halo: Hydrogen, Helium, Nitrogen, Methane, Neon.
- Trace Metal Ring: Iron, Copper, Nickel, Aluminum.

Resources respawn 12% faster, but movement is 4% slower.

### Warzone

Tags: contested, hazardous, high-value.

- Contested Ore Ring: Iron, Copper, Silicates, Titanium, Cobalt, Tungsten.
- Battlefield Gas: Hydrogen, Methane, Ammonia, Argon, Xenon.
- Strategic Fragments: Gold, Platinum, Rare Earths, Uranium.

Weapon range is increased 8%, shield regeneration is reduced 10%, and all exposed forces take 0.35 environmental damage per second. Bring durable ships and shorten logistics routes.

### Corsair Den

Tags: corsair, contested, high-value, relics.

- Raider Ore Cache: common resources plus Gold, Rare Earths, and Platinum.
- Black Gas: Methane, Ammonia, Hydrogen, Argon, Xenon.
- Hidden Heavy Metals: Titanium, Tungsten, Uranium, Cobalt.

Sensors are reduced 14%, while weapon range is increased 4%. Expect Corsair strategic interest.

### Empty Frontier

Role: player home. Tags: starter, balanced, protected home.

- Starter Metals: Iron, Copper, Nickel, Aluminum.
- Starter Minerals: Silicates, Ice, Carbon.
- Starter Fuel Drift: Hydrogen, Helium, Methane, Ammonia.

This system is protected from territory capture and includes the materials needed to reach manufacturing without leaving home.

### Binary Forge

Tags: metal-rich, industrial, contested.

- Foundry Belt: Iron, Nickel, Aluminum, Copper.
- Heavy Metal Arc: Cobalt, Titanium, Tungsten.

Mining is increased 18%, the strongest system-wide mining modifier in the release. Respawn is 8% slower and shield regeneration is 5% weaker.

### Volcanic Crucible

Tags: hot, heavy-metals, hazardous.

- Sulfurous Rubble: Sulfur, Iron, Carbon, Cobalt.
- Refractory Ring: Titanium, Tungsten, Uranium, Silicates.

Mining is increased 12%, movement is reduced 6%, and environmental damage is 0.80 per second. Use quick extraction plans, shield support, or heavy hulls.

### Nebula Expanse

Tags: gas-rich, sensor-interference, frontier.

- Common Gas Sea: Hydrogen, Helium, Methane, Nitrogen.
- Noble Gas Veil: Argon, Neon, Xenon, Ammonia.

Mining is increased 8%, but sensors fall to 72% and weapon range to 90%. Close-range ambushes and incomplete information are central risks.

### Shattered Worlds

Tags: mixed-resources, ruins, contested.

- Planetary Debris: Iron, Copper, Silicates, Carbon, Aluminum.
- Buried Technology Arc: Gold, Rare Earths, Platinum, Nickel.

Respawn is increased 15%, the highest in the release, while movement is reduced 3%. It is a strong general expansion target.

### Pulsar Reach

Tags: exotic, radiation, high-value.

- Irradiated Ore Ring: Titanium, Rare Earths, Platinum, Uranium, Tungsten.
- Ion Wake: Xenon, Argon, Neon, Helium.

Sensors increase 20%, but shield regeneration falls to 72% and environmental damage reaches 1.15 per second. Pulsar Reach is extremely valuable and extremely expensive to occupy.

### Carbon Basin

Tags: chemical, organic, fuel-feedstock.

- Carbonaceous Belt: Carbon, Phosphates, Sulfur, Silicates.
- Chemical Cloudbanks: Methane, Ammonia, Nitrogen, Hydrogen.

Mining is increased 6% and respawn 12%. This system supports polymer, propellant, explosive, coolant, and Fuel production.

### Ancient Graveyard

Tags: relics, rare-metals, salvage, high-value.

- Relic Field: Silicates, Gold, Platinum, Rare Earths, Titanium.
- Dormant Propellant Clouds: Xenon, Argon, Neon, Helium.

Sensors are reduced 12%, respawn is reduced 18%, and weapon range increases 6%. Its resources are valuable but replenish slowly.

## Territory-control states

Capturable static systems can be:

- Neutral
- Capturing
- Controlled
- Contested
- Protected

Player-home systems are always protected and ignore capture updates.

## Influence values

Only living ships and stations inside the central command zone contribute.

| Asset | Influence |
|---|---:|
| Station | 4.0 |
| Armed ship | 1.5 |
| Unarmed non-harvesting ship | 0.75 |
| Harvest-capable ship | 0.5 |

A faction needs at least **3.0 influence** to be eligible.

Examples:

- One station is enough.
- Two armed ships are enough.
- Six mining ships are enough.
- One armed ship plus three miners is enough.

## Capture timing and contesting

An uncontested capture takes 75 seconds at normal simulation time.

- If exactly one eligible faction occupies the command zone, its capture progresses.
- If more than one faction meets the minimum, the system becomes contested and progress stops.
- If no faction remains eligible, capture progress decays at 35% of the normal capture rate.
- A current controller remains controlled while it is the only eligible faction.

## Control bonuses

The system controller receives:

- Mining yield multiplier: `1.12` — a 12% bonus.
- Shield regeneration multiplier: `1.08` — an 8% bonus.

These bonuses apply only to assets owned by the current controller in the controlled system.

## Practical capture strategy

- A Deployer can establish a station that immediately exceeds the minimum influence threshold.
- Keep armed escorts inside the command zone while the station package deploys.
- Use the galaxy map to watch capture progress and incoming contesting forces.
- Avoid relying only on miners; six are needed for eligibility and they are vulnerable.
- Hazardous systems continue applying environmental pressure during capture.
- Control bonuses reward holding production systems, but do not compensate for every environmental penalty.