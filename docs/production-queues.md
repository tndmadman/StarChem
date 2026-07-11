# Station production queues

Each station owns one FIFO production queue shared by ships, station packages, manufactured items, and research.

## Job lifecycle

- Resources are removed from the station hangar when a job is funded.
- If the target hangar is short on resources but other owned hangars can supply them, a blocked production job enters the real queue immediately.
- Every request receives its own job ID, including repeated requests for the same item.
- Logistics requests are linked to those job IDs and fund the existing jobs in place after delivery, preserving FIFO order.
- The first job is active; all later jobs wait.
- A resource-blocked active job pauses until logistics funds it.
- A station that requires fuel pauses its active job while unpowered.
- Cancelling any funded job returns its reserved resources to that station's hangar. Cancelling an unfunded job removes its linked logistics request; cargo already in transit still reaches the target hangar.
- Waiting jobs can be moved up or down, but the active job cannot be reordered.
- Research cannot be moved ahead of its prerequisite.
- A station-package job reserves an empty Deployer in range. If that ship becomes unavailable, the job pauses until another eligible Deployer can be reserved.

## Configuration

Production duration remains data-driven:

- Ships: `buildTimeSeconds` on a ship definition.
- Station packages: `buildTimeSeconds` on the packaged station definition.
- Craftable items: `timeSeconds` on the craftable definition.
- Research: `timeSeconds` on the research topic.

A duration of `0` completes immediately and preserves compatibility with old definitions.

## Dev production timers

When Dev mode is active, the in-game **DEV CRAFTING** panel includes a **Disable production timers** checkbox. It is enabled by default for a new dev session and can be changed while the game is running. The authoritative host applies the setting to ship, station-package, manufacturing, and research queues. Costs, fuel requirements, combat, NPC behavior, and resource timers remain active.

## Logistics

Logistics requests only deliver missing resources. Each request is represented by a normal production job from the moment it is placed. When the station is funded, logistics marks that same job funded instead of creating a replacement at the end of the queue.

## Multiplayer

Clients send reliable `PROD` commands for enqueue, cancel, and reorder operations. The host validates station ownership and performs every mutation. Queue state and progress are encoded into each station's snapshot state, so clients render authoritative server data. Dev-approved clients send production-timer changes through the existing authoritative dev-command channel.
