# Multiplayer identity authentication

StarChem v1.7.0 uses multiplayer protocol 8. Authentication and session resume run only after the client has verified the server's TLS identity.

## Compatibility

Clients and servers must match the StarChem application version, multiplayer protocol, rules version, and packaged configuration fingerprint. StarChem v1.6.0 uses protocol 7 and is intentionally incompatible with v1.7.0 multiplayer.

Build commit is diagnostic. Different commits may connect only when all required compatibility values match.

## Password authentication

Retained player identities use a server challenge followed by a TLS-protected client credential.

- The client derives a server-scoped credential from the player password, verified TLS fingerprint, player name, and server-provided account salt.
- The credential is transmitted only through the authenticated TLS channel.
- The server hashes and verifies the supplied credential against retained authentication state.
- Authentication digests copied from a server save are not accepted as reusable proof keys and cannot authenticate by themselves.
- The server does not store the player's raw password.

A remote client receives the same initial `AUTH_CHALLENGE` packet shape whether the requested name is retained or unknown, so the handshake does not disclose which commander names already exist.

## Provisioning a new remote player

Remote clients cannot create a new retained identity directly. To provision a player:

1. On the server machine, launch a client that connects through loopback (`127.0.0.1` or `::1`).
2. Use the exact commander name and password the remote player will use.
3. Allow that local connection to complete once, creating the retained identity.
4. Disconnect the local client.
5. The player can then authenticate remotely with the same name and password.

This restriction prevents a remote caller from learning that a name is unclaimed by observing successful registration.

## Session resume

After successful authentication, the server may issue a raw random session token over the verified TLS connection.

- The client returns the token only through a later verified TLS session.
- The server stores a protected verifier rather than accepting save-extracted digest material as a credential.
- Current and bounded previous-token state support reconnect recovery without making old tokens valid indefinitely.
- Stale or invalid remembered sign-ins fall back to password entry instead of creating a second identity.
- Moderation, maintenance, whitelist, archive, kick, ban, and slot policy still apply after authentication.

## Remembered sign-ins

Reusable session tokens and server-scoped password credentials are not stored in ordinary `sessions.properties` data by default.

StarChem uses:

- Windows user-scoped DPAPI.
- macOS Keychain.
- Linux Secret Service.
- An owner-only file fallback with an explicit warning when an operating-system credential service is unavailable.

TLS fingerprints and the random client-device identifier remain separate non-secret metadata. Legacy plaintext remembered credentials are migrated into protected storage and removed from current, previous, temporary, recovery, and lock-file paths where supported.

The **Remember sign-in on this computer** option controls both the password-derived credential and reusable session token. **Clear remembered sign-ins** removes reusable authentication material without deleting TLS trust or the client device identity.

## Server files

The server stores a private authentication-decoy secret beside the save as:

```text
<save-name>-auth-decoy.key
```

Back up this file with the server save, administration files, moderation data, observations, activity journal, identity state, recovery state, TLS identity, and TLS password. Do not publish or share it. Replacing it does not change real player passwords, but it changes the synthetic salts returned for unknown identities.

The managed TLS identity normally uses:

```text
<save-name>-tls.p12
<save-name>-tls.password
```

Both files are sensitive. Read `TLS_IDENTITY_SECURITY.md` before moving, restoring, or replacing them.

## Attempt limiting and connection bounds

Unauthenticated JOIN attempts are limited independently by source address and normalized commander name. Pre-authentication connections are also bounded globally, by numeric address, and by normalized IPv4 /24 or IPv6 /64 subnet before expensive per-connection resources are allocated.

TLS and application authentication share an absolute deadline. Partial frames do not extend that deadline indefinitely. Successful authentication remains subject to normal server admission and moderation policy.
