# Station production queues

Each station owns one FIFO production queue shared by ships, station packages, manufactured items, and research.

## Job lifecycle

- Resources are removed from the station hangar when a job is queued.
- The first job is active; all later jobs wait.
- A station that requires fuel pauses its active job while unpowered.
- Cancelling any funded job returns its reserved resources to that station's hangar.
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

## Logistics

Logistics requests only deliver missing resources. Once the station is funded, logistics creates a normal production job instead of completing the output directly.

## Multiplayer

Clients send reliable `PROD` commands for enqueue, cancel, and reorder operations. The host validates station ownership and performs every mutation. Queue state and progress are encoded into each station's snapshot state, so clients only render authoritative server data.
