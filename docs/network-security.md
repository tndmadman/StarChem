# Network trust, TCP framing, and snapshot validation

StarChem multiplayer uses one full-duplex TCP connection per client. The authoritative host listens on the configured TCP port, and each client connects to the resolved server address and port.

## Connection trust and compatibility

A client resolves and pins the configured server address before starting the transport. Frames are accepted only from that connected socket. The server associates each accepted TCP connection with its remote endpoint while persistent player ownership remains protected by the session-token system.

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

Regular world snapshots are replaceable. When a newer regular snapshot is queued before the previous one is written, the older snapshot is removed and the new snapshot is appended at the correct point in the ordered stream. This prevents obsolete state from accumulating while preserving ordering relative to control messages.

Periodic full corrective snapshots are not replaceable. They contain complete resource state and force an authoritative correction, preventing a continuously slow connection from missing every full-resource repair. Initial state, session messages, developer-access changes, system deletion notices, and other control messages also remain ordered and non-coalesced.

## Disconnect and session recovery

Socket closure is reported immediately to the authoritative server. The TCP connection is temporary; the player session is not.

The server retains player identity, assets, research, production queues, home system, and view state for the configured disconnect grace period. A reconnecting client opens a new TCP connection and presents its stored resume token. Valid tokens are rotated after recovery, stale connections cannot reclaim an active session, and gameplay commands are blocked while reconnecting.

Application PING messages remain enabled to detect silent network partitions and maintain a useful connection-liveness signal in addition to TCP keepalive.

## Snapshot rejection

A snapshot is decoded and validated completely before live world state changes. The client rejects the whole snapshot when a section contains malformed rows, unexpected columns, excessive entity counts, duplicate IDs, unknown rule or enum values, invalid cargo, invalid production queues, non-finite numbers, or values outside protocol bounds.

Rejected frames do not advance the accepted snapshot sequence. A later valid snapshot can still be decoded and applied.

The development performance overlay reports TCP connections, frames and bytes, queued frames and bytes, coalesced snapshots, slow-client closures, malformed frames, connection rejections, inbound overflow, rejected snapshots, round-trip time, and snapshot age.

## NAT and port forwarding

Normal client-side NAT requires no special configuration. A host behind NAT must forward the selected **TCP** port to the server machine.
