# Networking and Security

StarChem v1.1.0-alpha replaced its former UDP transport and custom reliability layer with framed TCP synchronization.

## Architecture

- One full-duplex TCP connection per client.
- Authoritative host or dedicated server.
- Client-side prediction for responsive local presentation.
- Server-approved world, ownership, research, production, and view state.
- Temporary socket identity separated from persistent player-session identity.

## Connection trust

A client resolves and pins the configured server address before starting transport. Frames are accepted only from that connected socket.

Every accepted socket receives a process-local monotonic connection ID. Routing, compatibility state, ownership association, sends, and disconnect events use this ID. Remote address and port are retained for logging and access policy rather than being treated as durable identity.

Persistent player ownership is protected by the session-token recovery system.

## Compatibility handshake

Before normal gameplay messages are dispatched, both sides verify:

- Protocol version.
- Application version.
- Build commit.
- Rules version.
- SHA-256 fingerprint of release-critical configuration files.

Legacy, malformed, or mismatched clients are rejected before player state is created or authoritative world state is applied.

This means two packages with the same visible version can still be incompatible if the build commit or configuration fingerprint differs.

## TCP framing

TCP is a byte stream, so every StarChem message uses explicit framing:

1. Four-byte big-endian payload length.
2. UTF-8 payload bytes.
3. Maximum payload size of 512,000 bytes.
4. Strict UTF-8 decoding.

The transport rejects:

- Zero-length frames.
- Oversized frames.
- Truncated frames.
- Malformed UTF-8.

Reader threads decode complete frames and publish them to a bounded inbound queue. The game thread does not perform blocking socket reads.

## Connection and queue limits

- Maximum simultaneous TCP connections: 128.
- Inbound queues are bounded.
- Outbound frame counts and byte totals are bounded per connection.
- Slow clients cannot force the authoritative simulation to block on socket writes.

Each connection has a dedicated writer thread. The simulation enqueues messages and continues.

## Delivery classes and coalescing

Outbound traffic has explicit delivery classes.

Replaceable snapshot slots include:

- Regular world snapshots.
- Full correction snapshots.
- Initial and system-view snapshots.
- Leaderboards.
- Galaxy state.

Newer replaceable state can supersede obsolete queued state of the same class. Ordered control traffic is never coalesced.

Ordered traffic includes session messages, developer-access changes, system-deletion notices, commands, and other state transitions that must be processed in sequence.

Full correction snapshots use a separate slot from sparse regular snapshots so a sparse update cannot replace a required correction.

## Slow-client isolation

A client that cannot drain required ordered traffic is disconnected rather than being allowed to:

- Block the server simulation.
- Grow queues without limit.
- Delay healthy clients.
- Consume unbounded memory.

This isolates one backpressured client from the rest of the session.

## Disconnect and recovery

Socket closure is reported immediately, but the player session is retained during the configured grace period.

Retained state includes:

- Player identity.
- Ownership and assets.
- Inventory.
- Research.
- Production queues.
- Home system.
- Current view state.

A reconnecting client opens a new socket and presents its stored resume token. Valid tokens rotate after recovery.

Because disconnect events include the old connection ID, a delayed close event from an obsolete socket cannot detach the replacement socket. Stale connections cannot take over an active recovered session, and gameplay commands are blocked while reconnection is incomplete.

Application-level PING messages remain enabled to detect silent partitions in addition to TCP keepalive.

## Snapshot validation

A received snapshot is decoded and validated completely before live world state changes.

The entire snapshot is rejected for conditions including:

- Malformed rows.
- Unexpected columns.
- Excessive entity counts.
- Duplicate identifiers.
- Unknown rule identifiers or enum values.
- Invalid cargo or production queues.
- Non-finite numeric values.
- Values outside protocol bounds.

A rejected frame does not advance the accepted snapshot sequence. A later valid snapshot can still be accepted.

Atomic validation prevents a partially valid snapshot from leaving the client in a mixed or corrupted state.

## Remote-view revision safety

Every system-view request carries an increasing client revision. Full-view frames echo the latest accepted revision.

A client waiting for revision N rejects complete but obsolete responses for earlier revisions. This protects rapid request sequences such as A → B → A from delayed old responses.

Remote-view mode and request-pending state are tracked separately, allowing normal snapshots to keep updating the selected system after the switch completes.

## NAT and firewall behavior

Joining clients usually require no router configuration.

A host behind NAT must:

- Forward the selected TCP port to the server machine.
- Permit inbound TCP traffic through the operating system firewall.
- Keep the server machine’s LAN address stable.

StarChem no longer uses UDP for multiplayer.

## Confidentiality warning

TCP provides reliable, ordered delivery. It does **not** provide:

- Encryption.
- Confidentiality.
- Cryptographic server authentication.
- Protection equivalent to TLS.

Traffic can be observed on an untrusted network, and the protocol alone does not cryptographically prove the server’s identity. TLS or an external secure tunnel would be required for encrypted internet-facing transport.

## Developer authority

Launching a remote client with `--dev` does not grant authority by itself.

Remote developer tools require:

- Host authorization.
- A matching strong developer token where token authorization is used.
- A token containing 16–128 permitted characters: letters, numbers, `.`, `_`, `~`, or `-`.

A graphical host’s loopback client is authorized automatically. A dedicated server requires the token even for loopback clients. A graphical host can grant or revoke a connected client’s requested access from the in-game Remote dev access panel. Revocation takes effect on both client and server.

Never publish a developer token.

## Performance diagnostics

The developer performance overlay reports metrics including:

- TCP connections.
- Frames and bytes.
- Queued frames and bytes.
- Coalesced snapshots.
- Slow-client closures.
- Malformed frames.
- Connection rejections.
- Inbound overflow.
- Rejected snapshots.
- Snapshot age.

Press `F4` in developer mode to toggle the overlay.

## Validation coverage

The release verification suite includes:

- Multiple simultaneous clients.
- Command isolation.
- Slow-client isolation beside healthy clients.
- Abrupt socket loss and automatic recovery.
- Dedicated headless server operation.
- Stale connection-event handling.
- Rapid remote-view switching.
- Deterministic TCP smoke soak.
- Configurable extended soak workflow.

## Operational recommendations

- Use the official complete release package.
- Do not expose developer tokens.
- Forward only the required TCP port.
- Avoid editing release-critical JSON on only one participant.
- Monitor server logs for slow-client, malformed-frame, or compatibility rejections.
- Use a trusted network or protected tunnel when confidentiality matters.
- Stop dedicated servers with `SIGTERM` or `Ctrl+C` so transport shuts down cleanly.