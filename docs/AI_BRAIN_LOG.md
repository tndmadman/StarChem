# AI Brain Log

The AI brain log is enabled automatically by the authoritative simulation while the game is running in developer mode and the AI developer panel is being rendered.

Remote clients do not write a second AI journal. The file must be collected from the solo game or host machine that is actually simulating the NPC factions.

## Location

Logs are written relative to the StarChem working directory:

```text
logs/ai-brain/starchem-ai-<UTC session>-p<PID>-<nonce>-part001.jsonl
```

The AI developer panel displays the absolute path of the active file.

## Format

Each line is an independent JSON object. This JSON Lines format keeps earlier events readable if the game or computer closes unexpectedly.

Every record includes:

- `schema`: log schema version
- `seq`: event sequence within the session
- `utc`: real UTC timestamp
- `session` and `part`: rotation/session identity
- `source`: faction, world, developer tools, or brain logger
- `category`: event type
- `entity`: affected ship, station, node, faction, or system
- `message`: human-readable summary
- `world`, `system`, `systemName`, `gameTime`, and `seed` when a world is available
- `data`: structured event-specific details

## Important categories

- `ai_event`: strategy transitions and explicit decisions already emitted by AI systems
- `faction_state`: changed strategic summary, capacity, expedition, and deployer status
- `dev_settings`: pause, fast-AI, attack/economy disable flags, freezes, and difficulty preset
- `unit_spawn` / `unit_removed`: ship lifecycle
- `unit_intent`: task, target, package, mining assignment, logistics assignment, or order change
- `unit_position`: five-second checkpoints for moving or attacking ships
- `unit_health` / `unit_cargo`: damage, repair, loading, mining, and unloading deltas
- `base_spawn` / `base_removed`: station lifecycle
- `base_health` / `base_inventory`: station damage and material deltas
- `production_queue`: queue order, progress, blocking reason, reservation, or deployer change
- `base_logistics`: station logistics status changes
- `resource_seen` / `resource_change` / `resource_removed`: resource-node state
- `research_state`: completed-research changes
- `world_status`: game status messages that provide context for commands and failures
- `system_checkpoint`: periodic unit/base/resource counts and system control context
- `session_start`, `session_continue`, and `session_end`: file/session boundaries

## Rotation and retention

- Each segment rotates at approximately 16 MiB.
- Up to 24 recent `.jsonl` files are retained.
- JSON encoding, file writes, rotation, pruning, and flushes run on a dedicated daemon writer thread.
- The simulation submits immutable records through a bounded non-blocking queue.
- Under sustained pressure, position/checkpoint rows are dropped before the reserved critical-event capacity is consumed, and one `logger_backpressure` record reports the loss.
- The writer flushes at least every two seconds and drains for a bounded period on clean shutdown.
- Logging errors are shown in the AI developer panel and never propagate into the simulation.

## Performance guardrail

The producer path is validated with 800 AI ships, 80 stations, 160 forced delta captures, and 5,000 immediate events. The current implementation recorded a 7.191 ms p99 producer cost in that CI scenario, below the 8 ms developer-mode target. This is a regression guardrail rather than a guarantee for every machine or storage device.

The permanent logger validator also checks bounded queue behavior, priority preservation under backpressure, contiguous per-session ordering, clean shutdown and re-enable behavior, no writes after disable, and isolation of filesystem failures from the simulation.

## Sending a report

Reproduce the problem, close the game normally when possible, then provide every `partNNN.jsonl` file sharing the same session name. For a short reproduction there will normally be only `part001`.
