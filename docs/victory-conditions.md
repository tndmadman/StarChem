# Victory conditions

StarChem loads victory presets from `config/victory-conditions.json`. The graphical lobby lists every entry in that file under **Solo victory condition**. Dedicated servers select one by ID:

```text
java -Djava.awt.headless=true -jar StarChem.jar --server 50000 --name StarChem-Server --victory-condition fleet_muster
```

The selected ID is stored with the skirmish settings in a dedicated-server save. Existing saves keep their saved selection; use `--new-world` when intentionally starting a different victory setup.

## Built-in presets

| ID | Goal |
| --- | --- |
| `industrial_breakthrough` | Complete Advanced Industry research. |
| `research_supremacy` | Complete four research topics. |
| `fleet_muster` | Command 12 active ships. |
| `battle_ready` | Command 6 armed combat ships. |
| `station_network` | Operate 5 active stations. |
| `carrier_group` | Command 2 active carriers. |
| `laboratory_network` | Operate 3 active laboratories. |
| `fleet_power` | Reach 30,000 combined active ship and station HP plus shields. |
| `system_dominance` | Control 3 galaxy systems. |
| `endurance` | Keep at least one active asset alive for 30 minutes. |

## Modding

Each entry supports these fields:

```json
{
  "id": "fleet_muster",
  "displayName": "Fleet Muster",
  "description": "Command twelve active ships across the galaxy.",
  "type": "OWN_SHIPS",
  "value": "",
  "target": 12
}
```

- `id` must be unique and may contain lowercase letters, digits, `_`, `-`, or `.`.
- `displayName` and `description` are shown in the lobby and match HUD.
- `type` must be one of the supported evaluator types below.
- `value` is required by research, ship-type, and station-type goals.
- `target` must be a positive whole number.

Supported evaluator types:

- `COMPLETE_RESEARCH`
- `COMPLETE_RESEARCH_COUNT`
- `OWN_SHIPS`
- `OWN_COMBAT_SHIPS`
- `OWN_STATIONS`
- `OWN_SHIP_TYPE`
- `OWN_STATION_TYPE`
- `FLEET_POWER`
- `CONTROL_SYSTEMS`
- `SURVIVE_SECONDS`

The victory-condition file is registered in `config/starchem.json`, so it is included in the multiplayer configuration fingerprint. Multiplayer clients and servers must use matching configuration files. Malformed entries, duplicate IDs, invalid targets, unknown condition types, and unknown startup IDs are rejected instead of silently falling back.
