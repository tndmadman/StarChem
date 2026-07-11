# Station production queues

Each station owns one FIFO production queue shared by ships, station packages, manufactured items, and research.

## Job lifecycle

- Every accepted production request receives a unique queue entry immediately, including requests waiting on logistics deliveries.
- Resources are removed from the station hangar when a funded job is queued.
- An underfunded job keeps its original queue ID and position while logistics delivers its missing resources.
- The first job is active; all later jobs wait.
- A resource-blocked active job pauses until its linked logistics request funds that same job.
- A station that requires fuel pauses its active job while unpowered.
- Cancelling any funded job returns its reserved resources to that station's hangar.
- Cancelling an underfunded job removes its pending logistics request; resources already in transit still arrive in the target hangar instead of being destroyed.
- Waiting jobs can be moved up or down, but the active job cannot be reordered.
- Research cannot be moved ahead of its prerequisite.
- A station-package job reserves an empty Deployer in range once it is funded and able to run. If that ship becomes unavailable, the job pauses until another eligible Deployer can be reserved.

## Configuration

Production duration remains data-driven:

- Ships: `buildTimeSeconds` on a ship definition.
- Station packages: `buildTimeSeconds` on the packaged station definition.
- Craftable items: `timeSeconds` on the craftable definition.
- Research: `timeSeconds` on the research topic.

A duration of `0` completes immediately and preserves compatibility with old definitions.

## Logistics

Logistics requests only deliver missing resources. The production job is created before shuttles launch and remains in the station's normal FIFO queue. When all required resources are present, logistics reserves the cost on that exact job instead of creating a replacement job at the end of the queue.

Repeated requests for the same ship or item create separate jobs and separate logistics requests as long as other friendly hangars can cover each request.

## Multiplayer

Clients send reliable `PROD` commands for enqueue, cancel, and reorder operations. The host validates station ownership and performs every mutation. Queue state, progress, resource-blocked status, reserved state, carrier reservation, and blocked reason are encoded into each station's snapshot state, so clients only render authoritative server data.
