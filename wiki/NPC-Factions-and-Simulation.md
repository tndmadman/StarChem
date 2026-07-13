# NPC Factions and Simulation

StarChem v1.1.0-alpha ships with NPC AI version 5 and three enabled factions. NPC eligibility is scoped by system identity, role, and tags so faction behavior can differ across the galaxy.

## Faction overview

| Faction | Behavior | First spawn | Respawn | Primary role |
|---|---|---:|---:|---|
| Raiders | `RAIDER` | 18s | 45s | Reactive combat raid |
| Free Miners | `MINER` | 35s | 60s | Non-hostile resource extraction |
| Corsair Syndicate | `FACTION` | 65s | 90s | Full economy, research, expansion, and warfare |

## Raiders

Color: red `#FF5F55`

Starting force:

- 2 Frigates
- 1 Destroyer

Behavior:

- Attacks player ships and stations.
- Does not attack other NPC factions.
- Does not mine, research, manufacture, or replace workers.
- Maintains at most one station.
- Requires the player to have at least one combat ship before spawning.
- Uses a 1,250 defense range.
- Uses an 18-second raid cooldown.
- Does not retreat based on low health.

Spawn notice:

> Raider ships have entered the sector.

Raiders provide early military pressure without running a complete industrial economy.

## Free Miners

Color: yellow `#FFE066`

Starting force:

- 2 Prospectors

Behavior:

- Mines raw resources.
- Can replace workers up to a maximum of three.
- Does not attack ships, stations, or other NPC factions.
- Does not maintain a combat fleet.
- Maintains at most one station.
- Targets common metals, minerals, ice, and common gases.
- Uses a 1,250 defense range, but offensive attack flags are disabled.

Target materials include Iron, Copper, Nickel, Aluminum, Silicates, Ice, Carbon, Phosphates, Hydrogen, Helium, Methane, and Nitrogen.

Spawn notice:

> Independent miners have entered the sector.

Free Miners create economic competition and visible autonomous activity without deliberate aggression.

## Corsair Syndicate

Color: purple `#C77DFF`

Starting force:

- 2 Prospectors
- 1 Frigate

The Corsairs are the release’s complete NPC faction simulation.

### Economy and infrastructure

Workers:

- Prospector
- Maximum workers: 3

Industry ships:

- Deep Miner
- Gas Harvester
- Maximum industry ships: 2

Support ships:

- Hauler
- Freighter
- Salvager
- Maximum support ships: 3

Station packages:

- Shipyard
- Research Lab
- Manufacturing Plant
- Maximum stations: 4

The Corsairs can manufacture all 60 release recipes, including reclamation, intermediate components, power systems, weapons, and capital components. NPC industry recursively produces required subcomponents rather than stopping when an advanced cost is missing.

### Research

Configured Corsair research:

1. Advanced Industry
2. Combat Doctrine
3. Battlefleet Engineering

Supercapital Architecture is not included in the Corsair research list for this release.

### Fleet behavior

Fleet hulls:

- Frigate
- Destroyer
- Cruiser

Configured targets:

- Target fleet size: 6
- Raid fleet size: 4
- Harassment fleet size: 2
- Raid cooldown: 22 seconds
- Defense range: 1,400
- Retreat threshold: 35% health

The Corsairs:

- Attack player ships and stations.
- Attack other NPC factions.
- Replace workers.
- Harass workers.
- Prefer worker targets when configured behavior selects a harassment target.

### Resource scope

Corsairs target all 24 raw materials, from common Iron and Hydrogen through rare Platinum and exotic Uranium and Xenon.

### Expansion

The organized NPC galaxy director can launch cross-system expeditions and establish funded footholds. Expansion operates across eligible static systems rather than treating each system as an isolated encounter.

Spawn notice:

> Corsair Syndicate has established a foothold.

## NPC control of systems

NPC factions can contribute influence, contest systems, and become system controllers under the same central-zone rules used by players.

- Armed Corsair ships contribute armed-unit influence.
- Corsair stations contribute station influence.
- Multiple eligible owners make a system contested.
- NPC control receives the same system-control bonuses for owned assets.

## Per-system isolation

NPC runtime state and timers are isolated per system. Spawns, orders, stations, workers, fleet state, and economic activity in one system do not incorrectly share mutable runtime state with another system.

This matters when several copies of a template exist or when the galaxy is updating many systems at different simulation tiers.

## Background simulation

Inactive systems continue progressing through hot, warm, and cold scheduling tiers. Quiet systems update less frequently than the foreground system, but they do not freeze entirely.

Background progression includes relevant environment, resources, production, NPC activity, territory state, and cross-system director behavior. The scheduler reduces work for distant quiet systems while preserving long-term galaxy continuity.

## NPC manufacturing implications

The Corsair economy competes for raw resources and turns them into the same component chains used by players. This creates several strategic effects:

- Destroying industry can slow future fleet replacement.
- Raiding workers can deny raw inputs.
- Capturing industrial or gas-rich systems can deny valuable feedstocks.
- Salvaging NPC losses feeds the player’s own reclamation chain.
- A funded foothold can grow from workers and starter infrastructure into a researched combat presence.

## Disabling factions

Startup configuration supports disabled NPC faction IDs internally. A disabled faction is omitted from the relevant system’s NPC simulation. System-role and tag eligibility can also prevent a faction from appearing where it is not allowed.

Use release-consistent configuration in multiplayer. Changing NPC configuration changes the release-critical fingerprint and causes clients with different files to be rejected.

## Fighting NPC factions

- Raiders are a short-term combat check and do not have an economy to dismantle.
- Free Miners are non-hostile unless broader game conditions change ownership relationships; destroying them sacrifices a potential neutral presence.
- Corsairs should be treated as an expanding opponent: target workers, Manufacturing Plants, Shipyards, and expedition routes, not only the current fleet.
- Guard wormholes and command zones to detect footholds before they mature.
- Bring Salvagers after major battles to recover Scrap Metal, Hull Plating, and Circuit Fragments.