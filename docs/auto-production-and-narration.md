# Server auto-production and narration

## Server authority

Production planning runs only in the authoritative `World` simulation. Multiplayer clients still submit the normal build, package, manufacturing, and research commands. When the selected station and the player's other hangars cannot immediately cover the request, the server creates an auto-production plan.

The planner:

- resolves manufactured inputs recursively through the JSON recipe graph
- selects an owned compatible station in the same star system
- prefers operational stations with shorter queues
- queues prerequisite manufacturing through the existing production system
- uses the existing logistics shuttle system whenever materials are held in another hangar
- stops recursion at raw resources
- automatically resumes when missing raw resources enter an owned hangar

A plan remains server-side until its final ship, station package, manufactured item, or research job can enter the normal station queue.

## Temporary queue skipping

A station queue keeps its visible FIFO order. When the first job is waiting for resources, the server may temporarily run the first later job whose resources are already reserved. After the simulation update, that job returns to its original visible position unless it completed.

This prevents an unfunded future job from idling an otherwise usable station without permanently reordering the player's queue.

## Notifications

The server creates player-targeted structured notices. Remote notices are delivered with ordered TCP traffic; clients do not infer shortages from local snapshots.

Important state changes include:

- auto-production plan created
- raw-material shortage and exact amount
- missing research or specialized station
- prerequisite job queued
- final requested job queued

Repeated identical notices are rate-limited.

## Narration

Narration is a client presentation option. The dedicated server never opens an audio device or runs a speech engine. Narration is **disabled by default** and remains opt-in; an explicitly saved user preference is still respected on later launches.

Press **F8** in the graphical client to configure:

- narration on/off
- installed voice
- volume
- speech speed
- voice test

The voice test can be used while narration itself is disabled. Settings persist per operating-system user. StarChem detects and validates installed platform speech support before reporting a backend as available:

- Windows System Speech through Windows PowerShell or PowerShell when `System.Speech` is available
- macOS `say`
- Linux `espeak-ng` or `espeak` when installed

Backend detection is cached for the client session. If speech startup fails, StarChem reports the backend/process failure to stderr instead of silently dropping the request.

The authoritative notice marks whether a message is eligible for narration. That flag is preserved for remote clients over the ordered notice packet; actual speech playback still happens only on the graphical client and only when the local user has narration enabled.
