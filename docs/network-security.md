# Network trust and snapshot validation

StarChem currently uses UDP with application-level chunking and reliable-message wrappers. The protocol is intended for a client connecting to one configured server endpoint.

## Client endpoint pinning

When a client joins, the configured server hostname is resolved before the UDP transport starts. The client pins the resulting IP address and UDP port for the lifetime of that session.

Inbound datagrams from any other address or port are rejected before UTF-8 decoding, chunk reassembly, reliable ACK handling, or message dispatch. The endpoint is checked again at the dispatch boundary as defense in depth.

Reliable ACK packets are also compared with the destination address and port stored for the pending reliable message. An ACK from another endpoint cannot clear that pending message.

## NAT and port forwarding

Normal client-side NAT works without special handling. The client sends to the configured public server address and port, and accepts replies from that same address and port.

A server behind NAT must have its configured UDP port forwarded to the host. The public-facing port must remain stable during the session.

StarChem does not support servers that intentionally send replies from a different address or source port than the endpoint the client joined. Such replies are rejected rather than trusted automatically. Reconnect the client if DNS or the server endpoint changes.

Endpoint pinning prevents stray packets and ordinary off-path injection, but it is not encryption or cryptographic peer authentication. A future authenticated session token or encrypted transport can provide a stronger trust boundary.

## Datagram and chunk limits

The transport enforces limits before allocating or assembling untrusted payloads:

- maximum UDP datagram: 1,200 bytes
- maximum reconstructed message: 512,000 UTF-8 bytes
- maximum chunk payload: 900 UTF-8 bytes
- maximum chunks per message: 640
- maximum active assemblies: 64
- maximum buffered assembly data: 8,000,000 bytes
- assembly expiration: 10 seconds

Chunk IDs are length-limited. Duplicate chunks with conflicting content invalidate the assembly. Sending divides payloads by UTF-8 byte size rather than Java character count.

## Snapshot rejection

A snapshot is decoded and validated completely before live world state is changed. The client rejects the whole snapshot when any section contains malformed rows, unexpected columns, excessive entity counts, duplicate IDs, unknown rule or enum values, invalid cargo, invalid production queues, non-finite numbers, or values outside documented protocol bounds.

Rejected frames do not advance the accepted snapshot sequence. A later valid snapshot can still be decoded and applied.

The development performance overlay reports rates for rejected endpoints, rejected reliable ACKs, malformed packets, and rejected snapshots. Packet bodies are not logged by these counters.
