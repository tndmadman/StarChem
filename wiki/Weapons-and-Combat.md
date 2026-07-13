# Weapons and Combat

Combat is driven by configured weapon types, fixed ship loadouts, shield and hull durability, movement orders, formations, system modifiers, and territory bonuses.

## Weapon statistics

| Weapon | Range | Damage | Cooldown | Projectile behavior |
|---|---:|---:|---:|---|
| Point Defense Laser | 360 | 6 | 0.25s | Beam; defensive screen weapon |
| Light Railgun | 620 | 18 | 0.85s | Direct projectile |
| Heavy Cannon | 780 | 55 | 1.60s | Direct projectile |
| Fighter Strike | 920 | 80 | 2.40s | Strike attack |
| Capital Lance | 1,150 | 220 | 4.00s | Beam |
| Siege Lance | 1,400 | 420 | 5.50s | Beam |
| Light Missile | 820 | 36 | 1.80s | Moving, stoppable; speed 430; tracking 0.75 |
| Torpedo | 980 | 115 | 3.20s | Moving, stoppable; speed 310; tracking 0.45 |
| Capital Torpedo | 1,300 | 260 | 4.80s | Moving, stoppable; speed 250; tracking 0.30 |

Missiles and torpedoes are physical moving shots and can be stopped. Lower tracking values make heavy torpedoes less suited to agile targets.

## Ship loadouts

### Combat line

| Ship | Loadout |
|---|---|
| Frigate | 1 Light Railgun |
| Destroyer | 1 Light Railgun, 1 Light Missile, 1 Point Defense Laser |
| Cruiser | 1 Light Railgun, 1 Heavy Cannon, 1 Light Missile, 1 Torpedo |
| Battle Cruiser | 2 Heavy Cannons, 1 Torpedo |
| Battleship | 2 Heavy Cannons, 2 Torpedoes, 2 Point Defense Lasers |

### Capitals

| Ship | Loadout |
|---|---|
| Carrier | 1 Fighter Strike, 2 Point Defense Lasers |
| Dreadnought | 1 Capital Lance, 1 Capital Torpedo, 1 Heavy Cannon |
| Supercarrier | 2 Fighter Strikes, 2 Point Defense Lasers |
| Titan | 1 Capital Lance, 2 Heavy Cannons, 2 Point Defense Lasers |

### Megastructure

| Ship | Loadout |
|---|---|
| Monolith | 1 Siege Lance, 2 Capital Lances, 3 Point Defense Lasers |

## Direct attacks

Select one or more armed ships and right-click an enemy ship or station. Unarmed selected ships do not receive attack orders.

The status line reports whether:

- The attack order was accepted.
- The selected ship has no weapons.
- The target is not a valid enemy.

## Attack-move

1. Select ships.
2. Press `X`.
3. Right-click a destination.

Ships move toward formation-adjusted endpoints and engage threats according to the order system. Attack-move is useful for advancing through uncertain space without manually targeting every contact.

## Patrol

1. Select ships.
2. Press `P`.
3. Right-click the first patrol point.
4. Right-click the second patrol point.

Patrol creates a two-point route. Use it around wormholes, command zones, resource belts, and station approaches.

## Guard

1. Select ships.
2. Press `G`.
3. Right-click a friendly ship, friendly station, or map position.

Guard orders keep the selected force attached to a defensive anchor.

## Escort

1. Select escort ships.
2. Press `E`.
3. Right-click a friendly ship.

Escort is suited to Deployers, Freighters, specialist miners, and Salvagers moving through contested systems.

## Hold position

Select ships and press `H`. Hold is useful when maintaining exact influence inside a command zone or preventing a defensive screen from chasing targets away from a station.

## Fleet formations

Press `F` to cycle formations.

- **Grid:** compact rows and columns; default general-purpose movement.
- **Line:** ships spread horizontally around the destination.
- **Column:** ships stack vertically around the destination.
- **Wedge:** ships fan behind a leading point.

Formation spacing is applied to move, attack-move, patrol, and positional guard endpoints. Large mixed fleets may need extra room near stations and wormhole gates.

## Shields and regeneration

Shielded ships and stations have:

- Maximum shield capacity.
- Regeneration rate.
- Regeneration delay after taking damage.

System modifiers can change shield regeneration. The controller of a system receives another 8% shield-regeneration multiplier for its own assets.

Not every hull has a configured shield. Haulers, specialist miners, Freighters, and the Monolith rely primarily on hull durability unless another system provides protection.

## Range modifiers

System weapon-range modifiers affect engagement distance:

- Warzone: 1.08
- Corsair Den: 1.04
- Nebula Expanse: 0.90
- Ancient Graveyard: 1.06

Nebula Expanse also reduces sensor range to 0.72, making detection and engagement more constrained. Pulsar Reach increases sensors but imposes severe environmental damage and weak shield regeneration.

## Point defense

Point Defense Lasers are fast-firing screen weapons. Destroyers, Battleships, Carriers, Supercarriers, Titans, the Monolith, and some logistics hull costs include point-defense capacity or components.

Use point-defense-equipped ships to protect slower capital groups from stoppable missiles and torpedoes.

## Target matching

- Railguns and light missiles support mobile screening and anti-light work.
- Heavy cannons provide stronger medium-range direct damage.
- Torpedoes hit harder but travel more slowly and track less effectively.
- Fighter Strikes give carriers long reach.
- Capital and Siege Lances provide extreme range and burst damage.
- Dreadnoughts and Monoliths are siege platforms, not pursuit ships.

## Environmental combat

Continuous environmental damage applies to every exposed force in:

- Warzone: 0.35 damage/second.
- Volcanic Crucible: 0.80 damage/second.
- Pulsar Reach: 1.15 damage/second.

Long engagements in these systems cause attrition even before enemy damage. Pulsar Reach also reduces shield regeneration to 72%, making disengagement and repair timing critical.

## Territory warfare

The central command zone is the focal point of system capture. Combat considerations:

- Two armed ships produce the minimum 3 influence.
- A station produces 4 influence by itself.
- Multiple eligible factions make the system contested and stop progress.
- Hold-position and guard orders help keep forces inside the zone.
- Destroying or driving away enough influence can resume or reverse capture.
- The controller’s mining and shield bonuses create a defensive economic advantage.

## Practical fleet composition

A balanced mid-game force can combine:

- Frigates for speed and screening.
- Destroyers for missiles and point defense.
- Cruisers or Battle Cruisers for line damage.
- A Salvager behind the fleet for recovery.
- A Freighter or Hauler only when logistics are protected.

Capital groups benefit from:

- Point-defense escorts.
- Faster ships to catch scouts and light attackers.
- Dedicated logistics routes for replacement components.
- Command-zone discipline so slow capitals do not drift away from the objective.

## Audio and feedback

Procedural audio cues confirm selection, movement, harvesting, attack orders, errors, station placement, destruction, and wormhole transit. Toggle audio with `Ctrl+M`. Always read the HUD status line when a command appears to fail.