# StarChem v1.5.4

StarChem v1.5.4 is a multiplayer connectivity and dedicated-server save-state hotfix covering changes introduced after v1.4.0.

## Multiplayer Connectivity Hotfix

- Fixed clients being rejected solely because the client and server JARs were compiled from different Git commits while using the same application version, protocol, rules, and packaged configuration.
- Build commit remains visible in version and compatibility diagnostics, but it is no longer a hard connection requirement.
- Application version, network protocol, rules version, and packaged configuration fingerprint remain strict multiplayer compatibility requirements.
- Transport connection failures are now surfaced to the client instead of being silently retried until a generic timeout.
- Changed TLS certificate fingerprints now produce an immediate actionable connection failure while still refusing to send login secrets.

## Durable Dedicated Server Saves

- Added server-side save archives for dedicated/headless servers so player sessions, galaxy state, NPC runtime state, production state, research, resources, bases, units, and cross-system simulation data can survive server restarts.
- Added autosave controls, backup retention, save naming, save directory selection, and a `--new-world` startup option for intentionally replacing the current saved world.
- Added update-safe save loading with a manifest, content checksums, schema/version metadata, and fallback behavior for corrupted or incomplete current saves.
- Added migration scaffolding so future save formats can be upgraded without requiring old saves to be thrown away.
- Added save content resolution so persisted worlds can restore JSON-driven game data consistently with the packaged rule/config files.
- Ensured restored player sessions keep their player IDs, colors, password records, session token digests, home systems, galaxy map ownership, and retained disconnected state.

## Player Identity And Passwords

- Added per-server player passwords for multiplayer names, so a returning player must prove ownership of the saved commander identity instead of claiming a name directly.
- Added a password prompt in the lobby join flow for first-time server identity setup or returning-player password entry.
- Added a remember-password checkbox. When enabled, StarChem remembers the password-derived verifier for that server/player on this computer. When disabled, the verifier is kept only for the current launch and is not written to the local session file.
- Server saves store salted PBKDF2-HMAC-SHA256 password digests instead of raw passwords or fast unsalted hashes.
- Returning-player login now uses server nonce challenges and one-time HMAC proofs instead of sending reusable password verifier material after registration.
- Password rejection clears stale local saved session/auth data so the client can safely re-prompt instead of repeatedly retrying bad credentials.

## Encrypted Multiplayer Transport

- Multiplayer host/client startup now uses TLS sockets, so login, registration, resume, dev-token requests, gameplay commands, snapshots, and save-session tokens are encrypted on the wire.
- Dedicated and hosted servers generate and reuse a local PKCS12 TLS identity beside their save data.
- Clients pin the server certificate fingerprint on first contact and refuse changed fingerprints before sending login secrets.
- Generated save data and TLS key material are ignored by Git by default.
- Low-level transport validators still exercise the raw framed TCP codec separately, while real game startup uses encrypted transport.

## Reconnect And Session Security

- Session resume no longer sends the raw resume token in network packets.
- Resume now uses a server nonce challenge and one-time proof derived from the saved token digest.
- Resume tokens still rotate after successful reconnects, with a short replay window only for idempotent retry on the same active connection.
- Raw-token network resume attempts are converted to a challenge and cannot reclaim a player session by themselves.
- Active-session protection still prevents a second TCP connection from displacing a currently connected player.

## Dedicated Server And Simulation Persistence

- Added `GalaxyCoordinator`, `SystemSimulationScheduler`, and `SystemControlState` support for saving and restoring active multi-system simulation state.
- Added persistence hooks for organized NPC runtime systems, including strategic director, expeditions, expedition readiness, recovery, repair evacuation, squad combat, station construction, and production planning state.
- Player identities, research, ships, stations, inventories, production queues, home systems, and ownership now remain indefinitely when clients disconnect, time out, close the game, or leave normally.
- Removed automatic session-expiry deletion; only future explicit server administration commands may delete player save data.
- Restored server worlds avoid hidden local `SOLO` player leakage in authoritative dedicated-server sessions.

## Validation

- Added dedicated save-store validation for current saves, previous-save fallback, checksum failure handling, and restored persistent player sessions.
- Expanded TCP session recovery validation for retained player assets, token rotation, server restart recovery, password challenge rejection, and raw-token replay resistance.
- Added network-security validation for TLS startup, certificate pinning, changed-certificate refusal, framed TCP handling, compatibility rejection, snapshot coalescing, and slow-client backlog limits.
- Revalidated multiplayer join, automatic reconnect, server save restore, network hardening, and release hygiene after the merge.

## Compatibility

All multiplayer clients and servers must use StarChem v1.5.4 with matching network protocol, rules, and packaged configuration files. The embedded Git commit is diagnostic only and does not block otherwise compatible v1.5.4 builds. Older application versions remain rejected. Existing v1.4.0 dedicated servers do not have durable v1.5.4 server save archives; start v1.5.4 with the desired save directory/name and let the server create its first save.

## Security Notes

- StarChem now encrypts multiplayer traffic with self-hosted TLS and pins the first server certificate seen for each saved server/player identity.
- On a first connection to a new server, players should treat the server fingerprint like any first-contact trust decision. If the fingerprint changes later, StarChem refuses to send login material.
- Server owners cannot see raw player passwords from StarChem save files; saves contain salted PBKDF2 digests and token digests.

## Requirements

- Java 17 or newer.
- Extract the complete release ZIP before launching.
- Windows client: start with `run-starchem.bat`.
- Windows dedicated server: start with `run-starchem-server.bat`.
- Linux client: run `./run-starchem.sh`.
- Linux dedicated server: run `./run-starchem-server.sh`.
- Direct launch remains available through `java -jar StarChem.jar`.

## License

StarChem is proprietary software. Official unmodified compiled releases are licensed for personal, non-commercial use under the packaged `LICENSE`. Source code, configuration, assets, and other protected material may not be copied, modified, compiled, redistributed, sold, reused, or incorporated into another project without prior written permission.
