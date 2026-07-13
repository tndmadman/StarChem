# Network trust, TCP framing, and snapshot validation

StarChem multiplayer uses one full-duplex TCP connection per client. The authoritative host listens on the configured TCP port, and each client connects to the resolved server address and port.

## Connection trust and compatibility

A client resolves and pins the configured server address before starting the transport. Frames are accepted only from that connected socket. Every accepted socket receives a process-local monotonic connection ID. Routing, compatibility state, ownership, sends, and disconnect events use that ID; the remote address and port are retained only for logging and access policy. Persistent player ownership remains protected by the session-token system.

Before normal multiplayer messages are dispatched, both sides verify the protocol version, application version, build commit, rules version, and configuration fingerprint. A mismatch is rejected before player state is created or authoritative state is applied.

TCP provides reliable, ordered byte delivery, but it does not provide encryption or cryptographic server identity. Traffic is not confidential. TLS would be required for encrypted internet-facing transport.

## Framing and limits

TCP is a byte stream, so every StarChem message uses explicit framing:

- 4-byte big-endian payload length
- UTF-8 payload
- maximum payload: 512,000 bytes
- strict UTF-8 decoding

Zero-length, oversized, truncated, and malformed UTF-8 frames are rejected. Reader threads decode complete frames and publish them to a bounded inbound queue; the game thread never performs blocking socket reads.

The host supports at most 128 simultaneous TCP connections. Each connection has bounded outbound frame and byte limits. A client that cannot drain ordered control traffic is disconnected instead of being allowed to block the authoritative simulation or consume unbounded memory.

## Snapshot delivery and backpressure

Each TCP connection has its own writer thread. The game thread only enqueues messages and therefore cannot block on a slow client socket.

Outbound messages carry an explicit delivery class instead of inferring behavior from packet text. Regular world snapshots, full corrections, view snapshots, leaderboards, and galaxy state each have independent replaceable slots. Ordered control messages are never coalesced. A replacement is appended at the correct point in the stream, preserving ordering relative to control messages.

Periodic full corrective snapshots have a separate replacement slot from sparse regular snapshots, so sparse state can never replace a corrective frame. Initial and view snapshots also use their own slot. Session messages, developer-access changes, system deletion notices, commands, and other control traffic remain ordered and non-coalesced.

## Disconnect and session recovery

Socket closure is reported immediately to the authoritative server. The TCP connection is temporary; the player session is not.

The server retains player identity, assets, research, production queues, home system, and view state for the configured disconnect grace period. A delayed close event from an older socket cannot detach a replacement socket because the event carries the old connection ID. A reconnecting client opens a new TCP connection and presents its stored resume token. Valid tokens are rotated after recovery, stale connections cannot reclaim an active session, and gameplay commands are blocked while reconnecting.

Application PING messages remain enabled to detect silent network partitions in addition to TCP keepalive.

## Snapshot rejection

A snapshot is decoded and validated completely before live world state changes. The client rejects the whole snapshot when a section contains malformed rows, unexpected columns, excessive entity counts, duplicate IDs, unknown rule or enum values, invalid cargo, invalid production queues, non-finite numbers, or values outside protocol bounds.

Rejected frames do not advance the accepted snapshot sequence. A later valid snapshot can still be decoded and applied.

The development performance overlay reports TCP connections, frames and bytes, queued frames and bytes, coalesced snapshots, slow-client closures, malformed frames, connection rejections, inbound overflow, rejected snapshots, and snapshot age.

## NAT and port forwarding

Normal client-side NAT requires no special configuration. A host behind NAT must forward the selected **TCP** port to the server machine.


## View switching

Every view request carries a monotonically increasing client revision. Full-view frames echo the latest accepted revision. A client waiting for revision N rejects complete but obsolete responses for earlier revisions, including A → B → A request races. Remote-view mode and request-pending state are tracked separately so normal snapshots continue updating the selected remote system after the switch settles.

## Multiplayer validation

Normal verification covers simultaneous clients, command isolation, one backpressured client beside healthy clients, abrupt socket loss with automatic full-path resume, dedicated headless servers, stale connection events, rapid view switching, and a deterministic smoke soak. The separate `tcpSoak` Gradle task and scheduled workflow run the same workload for an extended duration with a reproducible seed.
