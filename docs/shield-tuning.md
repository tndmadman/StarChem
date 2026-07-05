# Shield Tuning Table

This table is for quick balance review before playtesting. Values marked fallback come from the rules loader when a ship JSON file does not define explicit shield fields.

## Ship shields

| Ship | HP | Shield | Regen/s | Delay | Source |
|---|---:|---:|---:|---:|---|
| Prospector | 100 | 45 | 2.0 | 3.5s | explicit |
| Deployer | 240 | 140 | 4.0 | 5.0s | explicit |
| Scout | 70 | 50 | 4.0 | 2.5s | explicit |
| Hauler | 150 | 53 | 1.8 | 4.0s | fallback |
| Deep Miner | 180 | 63 | 2.2 | 4.0s | fallback |
| Gas Harvester | 125 | 44 | 1.5 | 4.0s | fallback |
| Freighter | 360 | 126 | 4.3 | 4.0s | fallback |
| Frigate | 180 | 140 | 8.0 | 3.0s | explicit |
| Destroyer | 280 | 220 | 9.0 | 3.5s | explicit |
| Cruiser | 520 | 420 | 12.0 | 4.0s | explicit |
| Battle Cruiser | 850 | 720 | 15.0 | 4.5s | explicit |
| Battleship | 1400 | 1200 | 18.0 | 5.0s | explicit |
| Carrier | 2600 | 2400 | 28.0 | 6.0s | explicit |
| Dreadnought | 3400 | 3200 | 24.0 | 7.0s | explicit |
| Supercarrier | 6200 | 6500 | 45.0 | 7.0s | explicit |
| Titan | 12000 | 14000 | 70.0 | 8.0s | explicit |
| Monolith | 32000 | 11200 | 384.0 | 4.0s | fallback |

## Station shields

| Station | HP | Shield | Regen/s | Delay | Source |
|---|---:|---:|---:|---:|---|
| Outpost | 1200 | 800 | 12.0 | 5.0s | explicit |
| Shipyard | 2400 | 1800 | 20.0 | 6.0s | explicit |

## Tuning notes

- If early fights feel too slow, lower frigate and destroyer shields first.
- If long range shots feel weak, lower shield regen before raising weapon output.
- If capital fights feel too long, increase capital delay before lowering capital shield amount.
- Monolith uses fallback values right now; tune it explicitly after normal capital fights feel good.
