# StarChem v1.6.0

StarChem v1.6.0 adds a full dedicated-server administration, moderation, diagnostics, developer, and recovery console on top of every multiplayer connectivity, TLS, session-security, and durable-save fix shipped in v1.5.7.

## Dedicated Server Console

- Added an interactive headless-server console whose commands are queued and executed on the authoritative server tick.
- Added server status, uptime, performance, JVM health, disk health, network diagnostics, player/session inspection, leaderboard, system inspection, asset inspection, production inspection, faction inspection, and sanitized state dumps.
- Added manual saves, runtime autosave controls, checksum-verified backups, backup verification, retention pruning, scheduled shutdowns, targeted notices, broadcasts, resynchronization, and safe simulation pause.
- Added protected abandoned-system pruning with preview, explicit confirmation, a fresh save, and a verified pre-operation backup.

## Moderation And Admission

- Added persistent maintenance mode, player-slot limits, MOTD, whitelist, temporary kicks, permanent or expiring bans, and moderation history.
- Admission policy now applies consistently to new JOIN requests, password-based identity reclaim, token resume, and proof-based resume.
- Added player-identity, exact IPv4/IPv6, CIDR, and StarChem client-device bans.
- A player ban records the active numeric IP and StarChem device identifier when those signals are available.
- Kicked and banned players retain their identity, research, ships, stations, inventories, production queues, systems, and save data.
- Added persistent last-seen player IP and device observations for moderation of offline identities.
- `ban mac` is an alias for the StarChem client-device identifier; remote hardware Ethernet/Wi-Fi MAC addresses are not available across internet routers.

## Runtime Developer And Recovery Controls

- Added process-local developer mode that can be enabled and disabled from the trusted local server console without restarting.
- Separated remote developer authorization from free construction.
- Disabling runtime developer mode revokes remote grants, removes free-build, restores normal AI controls, resets the difficulty preset, and restores startup production-timer behavior.
- Added authoritative resource inspection, grants, removal, setting, filling, and clearing for individual or all player bases.
- Added research inspection, prerequisite-aware grants, queued-job completion, cascading revocation, and reset operations.
- Added AI pause, speed, stepping, player/NPC freezing, attack/economy controls, difficulty presets, snapshots, and hot reload.
- Added generalized NPC faction inspection, spawning, removal, reset, funding, and forced strategic actions.
- Added production funding, completion, cancellation, reordering, and protected queue clearing.
- Added ship/base healing, protected destruction, ship relocation, player-wide repair, player relocation, respawn, and validated ship/base spawning.
- Destructive and high-risk recovery operations require explicit confirmation and create verified backups where appropriate.

## Persistence And Audit

- Added persistent administration state beside the server save.
- Added persistent whitelist, kick, and ban state.
- Added a bounded persistent operator activity journal with sensitive arguments and notice contents redacted.
- Added persistent player observation metadata for moderation use.
- Existing v1.5.7 world saves remain the base save format; the new administration files are separate companion files and do not discard existing player or galaxy state.

## Multiplayer Protocol 7

- Advanced the multiplayer protocol from 6 to 7 so JOIN and RESUME handshakes can carry the StarChem client-device identifier.
- Build commit remains diagnostic only; matching protocol, application version, rules version, and packaged configuration remain the compatibility requirements.
- v1.6.0 clients and servers must be used together. Published v1.5.7 clients and servers use protocol 6 and are intentionally incompatible with v1.6.0 multiplayer sessions.

## Included v1.5.7 Connectivity And Save Fixes

- Same-machine servers reached through `127.0.0.1`, `::1`, or another loopback address automatically replace stale TLS certificate pins without prompting.
- Graphical HOST mode and local dedicated-server joins no longer show an unnecessary certificate replacement prompt after an update or local TLS-key regeneration.
- Certificate changes from non-loopback remote servers remain blocked before login secrets are sent and require explicit confirmation.
- Local-host authentication, resume sessions, and certificate trust remain isolated and process-only so they cannot overwrite dedicated-server credentials.
- Server certificate trust remains scoped to the server endpoint, with migration from earlier per-commander pins.
- Clients compiled from different Git commits remain compatible when application version, protocol, rules, and packaged configuration match.
- Transport connection failures and changed-certificate failures remain visible and actionable to the player.
- Dedicated-server worlds retain player identities, research, ships, stations, inventories, production queues, home systems, ownership, galaxy state, NPC runtime state, and cross-system simulation state.
- Offline player saves remain indefinitely; normal disconnects and long offline periods do not delete player state.

## Security

- Multiplayer traffic remains encrypted with self-hosted TLS.
- Remote changed-certificate fingerprints are rejected before StarChem sends login secrets.
- Player passwords remain stored as salted PBKDF2-HMAC-SHA256 digests rather than raw passwords.
- Session resume continues to use nonce challenges and one-time proofs rather than sending raw resume tokens.
- Client-device identifiers and IP bans are best-effort moderation signals: device identifiers can be reset or spoofed, IP addresses can change or be hidden, and shared addresses may affect multiple players.
- Developer mutations are accepted only through trusted server authority and execute on the authoritative tick.

## Validation

- Added command-dispatch and persistence validation for administration, moderation, IPv4/IPv6 CIDR matching, stable device identity, and protocol-7 handshakes.
- Added real connected-client validation for runtime developer authorization, free-build separation, resources, production, research, spawning, healing, notices, resynchronization, and runtime shutdown.
- Revalidated network security, TLS identity lifecycle, loopback trust, password reclaim, token/proof resume, long-offline session recovery, simultaneous clients, reconnect, dedicated-server startup/shutdown, and save restoration.
- Revalidated reproducible JAR output, extracted Linux packages, release checksums, and Windows launchers.

## Compatibility

All multiplayer clients and servers must use StarChem v1.6.0 with network protocol 7 and matching rules and packaged configuration files. Build commit differences do not block otherwise compatible v1.6.0 builds. StarChem v1.5.7 uses protocol 6 and cannot join a v1.6.0 server or accept a v1.6.0 client.

Existing v1.5.7 dedicated-server save directories should be backed up in full before upgrade, including the world save, previous save, backups, and `*-tls.p12` identity. Start v1.6.0 with the same save directory and save name to retain the existing world and TLS identity.

## Requirements

- Java 17 or newer.
- Extract the complete release ZIP before launching.
- Windows client: start with `run-starchem.bat`.
- Windows dedicated server: start with `run-starchem-server.bat`.
