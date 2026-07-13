# Controls and Interface

## Camera

| Input | Action |
|---|---|
| `W`, `A`, `S`, `D` | Pan camera |
| Arrow keys | Pan camera |
| Mouse wheel | Zoom toward or away from the pointer |
| `M` | Open or close the galaxy map |
| `Escape` | Close the galaxy map or cancel the active command mode |

Camera movement is paused while the galaxy map is open.

## Selection

| Input | Action |
|---|---|
| Left-click a friendly ship | Select it |
| Left-click empty space | Clear or change selection according to the clicked object |
| Left-drag | Box-select friendly ships |
| Double-click a friendly ship | Select all visible friendly ships of the same type |
| Left-click a friendly station | Open its build, research, or manufacturing menu |
| Left-click an enemy | Show its identity and status |

## Context-sensitive right-click

Right-click behavior depends on the target:

- **Empty space:** move selected ships using the current formation.
- **Resource node:** assign compatible selected ships to automatic harvesting.
- **Enemy ship or station:** order selected armed ships to attack.
- **Wormhole:** move selected ships directly into the gate.
- **Command mode target:** complete the selected attack-move, patrol, guard, or escort order.

A Prospector can harvest both silicate rocks and gas clouds. Deep Miners only harvest silicate rocks; Gas Harvesters only harvest gas clouds.

## Fleet orders

| Key | Order | Use |
|---|---|---|
| `X` | Attack-move | Right-click a destination; ships advance and engage enemies encountered en route. |
| `P` | Patrol | Right-click the first point, then the second point. |
| `G` | Guard | Right-click a position, friendly ship, or friendly station. |
| `E` | Escort | Right-click a friendly ship. |
| `H` | Hold | Immediately order selected ships to hold position. |
| `F` | Formation | Cycle the active fleet formation used for movement orders. |

Press `Escape` to cancel a command before assigning its target.

## Overlays and shortcuts

| Key | Function |
|---|---|
| `I` | Toggle the resource catalog |
| `M` | Toggle the galaxy map |
| `R` | Toggle miner-range display |
| `Ctrl+M` | Mute or unmute procedural game audio |
| `F3` | Toggle AI debug overlay in developer mode |
| `F4` | Toggle performance overlay in developer mode |

### Resource catalog

The resource catalog lists every loaded material and shows:

- Display name, family, tier, and color.
- Whether the material is raw, manufactured, refined, or salvage.
- System templates and roles where a raw resource can naturally appear.
- Resource-node types that can contain it.

Manufactured and salvage materials are identified separately because they do not naturally spawn in belts.

### Galaxy map

The map shows system nodes, links, controller information, control state, and capture progress. Controlled systems use controller-colored rings. Clicking a system changes the viewed system when permitted. Remote multiplayer views are authoritative and revision-protected so stale responses do not replace a newer selection.

## Station menus

### Outpost

Builds starter Prospectors and Deployers. It also packages Shipyards, Research Labs, and Manufacturing Plants for transport by a Deployer.

### Shipyard

Builds player ships allowed by completed research. A visible hull may still be blocked by missing research or resources.

### Research Lab

Runs research topics and consumes Fuel while operating. Research is sequentially gated.

### Manufacturing Plant

Displays recipes by category:

- Processed materials
- Chemicals
- Electronics
- Industrial assemblies
- Power and defense
- Weapons
- Capital systems

Recipes can be hidden or blocked until required research is complete.

## Deployer and station-package behavior

A Deployer carries one station package. After placing the packaged station, the carrier is removed. Treat Deployers as single-use expansion units and avoid sending an unescorted package through dangerous systems.

## HUD information

The upper HUD reports:

- Local player name.
- Active system.
- Selected ship count.
- Current world or connection status.
- Network role/status.
- Formation, miner-range display, audio state, and command mode.

Read the status line after an order fails. It usually identifies whether no ship is selected, a target is invalid, a unit cannot perform the requested work, resources are missing, or research is incomplete.