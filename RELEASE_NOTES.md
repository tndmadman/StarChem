# StarChem v1.7.0

StarChem v1.7.0 is a security, persistence, multiplayer, server-operations, simulation-scheduling, production-correctness, and ship-fitting release. It advances multiplayer to protocol 13, rules version 27, and save format 5 while changing how reusable authentication material is generated, transmitted, stored, and resumed.

## Multiplayer Protocol 13

- Advanced the multiplayer protocol from 7 to 13 for the revised TLS-protected credential and session-token handshake, authoritative ship-fit catalogs, and current snapshot/save compatibility contract.
- Compatibility checks continue to require matching application version, protocol version, rules version, and packaged configuration fingerprint.
- Build commit remains diagnostic. Builds from different commits may connect only when every required compatibility value matches.
- StarChem v1.6.0 clients and servers use protocol 7 and are intentionally incompatible with v1.7.0 multiplayer.
- Older or malformed JOIN, RESUME, and WELCOME packets are rejected with bounded compatibility diagnostics.

## Authentication And Session Security

- Replaced save-digest-keyed authentication proofs with TLS-protected client credentials that are verified and hashed again by the server.
- Replaced proof-key-style session data with raw random session tokens delivered only through the verified TLS channel.
- Preserved bounded previous-token reconnect grace without allowing authentication fields copied from a server save to be replayed as credentials.
- Added validation that extracts authentication fields from a real save and confirms they cannot authenticate.
- Added account-enumeration resistance so retained and unknown names receive the same initial challenge shape.
- Added bounded authentication attempt limiting by source address and normalized commander name.
- Remote clients cannot create retained identities directly. New remote identities must first be provisioned through a trusted loopback connection.
- Stale remembered sign-ins now return the player to password entry instead of falling through to an unusable registration path.

## Remembered Client Credentials

- Moved reusable session tokens and server-scoped password credentials out of ordinary `sessions.properties` storage.
- Use Windows user-scoped DPAPI, macOS Keychain, or Linux Secret Service when available.
- Use an explicitly warned owner-only file fallback when an operating-system credential service is unavailable.
- Keep TLS trust fingerprints and the client device identifier separate from reusable authentication secrets.
- Migrate legacy remembered credentials into protected storage and sanitize current, previous, temporary, recovery, and lock files.
- The **Remember sign-in on this computer** option now controls both the reusable session token and password-derived credential.
- Added **Clear remembered sign-ins** without removing TLS trust or the client device identity.

## TLS And Private Storage

- Replaced the shared managed-PKCS12 password with a random password generated for each server installation.
- Store the managed TLS identity and password in separately protected files with verified POSIX permissions or Windows ACLs.
- Preserve trusted Windows SYSTEM and Administrators access without granting unrelated users broad access.
- Migrate legacy managed identities without changing the pinned certificate fingerprint.
- Support operator-provided PKCS12 identities, protected password files, and explicit key aliases.
- Fail closed for corrupt, unreadable, insecure, ambiguous, or unprotectable identities and credential files.
- Moved graphical client and dedicated-server data to separate per-user storage locations on Windows.

## Network And Server Hardening

- Added global, address, IPv4 /24, and IPv6 /64 limits before expensive per-connection resources are allocated.
- Moved TLS handshakes through a bounded executor and enforced an absolute authentication deadline.
- Added fair per-client inbound scheduling with global, per-client, packet-count, and elapsed-time budgets.
- Added command throttling, replaceable MOVE coalescing, stale-work cleanup, and queue diagnostics.
- Bounded save-archive expansion, JSON parsing, companion-file reads, subprocess output, packet diagnostics, and untrusted text handling.
- Normalize and escape player-controlled terminal text so ANSI, OSC, control, bidi, and invisible formatting cannot affect the operator console.

## Save And Persistence Reliability

- Moved routine save encoding, checksums, ZIP compression, verification, backup rotation, fsync, and promotion off the authoritative simulation tick.
- Serialize save work through a bounded single-writer persistence coordinator and coalesce overlapping autosaves.
- Keep shutdown and transaction-critical saves synchronous by awaiting the queued result.
- Require strict save archive structure, checksum, UTF-8, JSON, duplicate-key, and resource-limit validation before promotion.
- Retain verified current and previous companion-state copies and recover from malformed current state where possible.
- Persist pruned galaxy state before reporting success and restore the verified pre-operation backup when a prune transaction fails.
- Added collision-safe archive naming and stronger activity-journal rollover validation.
- Current v1.7.0 saves use save format 5 and retain exact runtime fit catalogs, construction selections, and refit reservations.

## Identity, Admission, And Administration

- Added durable retained-identity creation time, last-seen time, archive state, restore, dormant listing, and backup-protected permanent deletion.
- Preserve a monotonic player-ID high-water mark so deleted identifiers are never recycled.
- Whitelist admission now uses immutable retained player IDs rather than names that could later be claimed by another identity.
- Added bounded pre-authentication diagnostics and improved connected/retained session inspection.
- Added observation retention, age display, pruning, per-player deletion, and confirmed clear-all controls.
- Observation, administration, moderation, recovery, and identity companion files use the same verified private-storage policy.
- Developer tokens can be loaded from protected files instead of process arguments.
- Runtime developer grants and pending client requests are now represented independently.

## Simulation Scheduling And NPC Budgets

- Replaced per-tick full-galaxy inactive-system traversal with due-time scheduling for hot, warm, cold, and dormant systems.
- Kept actively viewed remote systems visually current while preserving their bounded gameplay-simulation tier.
- Prevented small resource corrections from rewinding client-predicted orbital phase.
- Reworked NPC resource budgeting to allocate priorities against one shared remaining inventory view.
- Cache one immutable galaxy-wide NPC budget frame across a traversal and update it after successful spending instead of rescanning every system for each decision.
- Added focused scheduler, remote-view continuity, budget reuse, mutation refresh, and multi-system traversal validation.

## Production And Gameplay Correctness

- Evaluate every unlocked recipe capable of producing a missing material instead of selecting only the first configured recipe.
- Prefer fundable and viable prerequisite routes while preserving deterministic configuration-order tie-breaking.
- Allow reclamation routes to satisfy production requests when standard inputs are unavailable.
- Added focused validation for alternate-recipe selection, prerequisite planning, queue deduplication, and production persistence.
- Improved authoritative remote-system visibility, movement isolation, numerical command validation, notification handling, and reconnect convergence.

## Ship Fits, Construction, And Refitting

- Added authored hull variants plus private and server-published player-created weapon and utility-module fits.
- Added Fit Studio workflows to create, save, publish, import, construct, and refit ships while keeping built-in fits read-only.
- Made the server authoritative for component compatibility, research, hardpoint capacity, installation cost, construction cost, conversion cost, service timing, and runtime fit IDs.
- Added transactional station refits across Outposts and Shipyards, including remote recall, station selection, exact material reservations, rollback, cancellation, destruction refunds, and restart persistence.
- Added world-scoped runtime fit catalogs so separate worlds and client server-switches cannot leak custom definitions.
- Unified authoritative combat, client prediction, orders, and rendering around one fitted attack-range calculation.
- Added explicit weapon hardpoints and strict fail-closed enum parsing for current configuration.

## Launcher And Package Changes

- The graphical lobby now provides **SOLO** and **JOIN**. Multiplayer hosting runs through the separate dedicated-server launcher.
- Added a repository-root `run-starchem-server.bat` and retained packaged Windows and Linux player/server launchers.
- Windows dedicated-server data defaults to `%LOCALAPPDATA%\StarChem\server` and is separate from graphical client saves.
- The controlled release package contains the JAR, configuration, player and server launchers, README, license, third-party notices, authentication documentation, TLS documentation, and the v1.7.0 upgrade guide.

## Upgrade From v1.6.0

1. Stop the v1.6.0 dedicated server cleanly.
2. Back up the complete server-data directory, not only the current `.starchem-save` archive.
3. Preserve current and previous saves, timestamped backups, TLS identity files, administration state, moderation state, observations, activity journal, recovery files, retained-identity state, and authentication-decoy state.
4. Extract v1.7.0 into a new application directory.
5. Start v1.7.0 with the same server-data directory and save name.
6. Confirm the server reports the expected world, retained identities, player assets, research, production, systems, fitted ships, active jobs, and TLS fingerprint before accepting remote players.

Read `UPGRADING_TO_1.7.0.md` before upgrading or attempting rollback.

## Compatibility

All multiplayer clients and servers must use StarChem v1.7.0 with network protocol 13, rules version 27, save format 5, and matching packaged configuration files. StarChem v1.6.0 uses protocol 7 and cannot join a v1.7.0 server or accept a v1.7.0 client.

Save-format migration upgrades existing v1.6.0 worlds and identities to save format 5, but a full directory backup is required because v1.7.0 adds and updates security-sensitive companion state and authoritative fit data. Do not assume that a v1.6.0 binary can safely interpret files written or migrated by v1.7.0.

## Requirements

- Java 17 or newer.
- Extract the complete release ZIP before launching.
- Windows client: start with `run-starchem.bat`.
- Windows dedicated server: start with `run-starchem-server.bat`.
- Linux client: start with `./run-starchem.sh`.
- Linux dedicated server: start with `./run-starchem-server.sh`.
