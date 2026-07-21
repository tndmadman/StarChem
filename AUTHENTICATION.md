# Multiplayer identity authentication

StarChem retained player identities use challenge-response authentication. A remote client receives the same `AUTH_CHALLENGE` packet shape whether the requested name is retained or unknown, so the initial handshake does not disclose which player names exist.

## Provisioning a new remote player

Remote clients cannot create a new retained identity directly. To provision a player:

1. On the server machine, launch a client that connects through loopback (`127.0.0.1` or `::1`).
2. Use the exact player name and password the remote player will use.
3. Allow that local connection to complete once, creating the retained identity.
4. Disconnect the local client. The player can then authenticate remotely with the same name and password.

This restriction prevents a remote caller from learning that a name is unclaimed by observing a successful registration.

## Server files

The server stores a private authentication-decoy secret beside the save as:

```text
<save-name>-auth-decoy.key
```

Back up this file with the server save, administration files, moderation data, observations, activity journal, and TLS identity. Do not publish or share it. Replacing it does not change real player passwords, but it changes the synthetic salts returned for unknown identities.

## Attempt limiting

Unauthenticated JOIN attempts are limited independently by source address and normalized player name. The limiter is bounded in memory and uses an expiring window. Successful authentication still remains subject to the server's moderation, whitelist, maintenance, and slot policies.
