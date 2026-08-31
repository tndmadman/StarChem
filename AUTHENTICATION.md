# Multiplayer identity authentication

StarChem v1.8.0 uses multiplayer protocol 17, rules version 27, and save format 6. Authentication and session resume occur only after the client has established TLS and verified the server identity it has pinned for that endpoint.

For server-certificate storage, migration, and operator-supplied keystores, read `TLS_IDENTITY_SECURITY.md`. For a persistent-server upgrade from the published v1.7.0 release, read `UPGRADING_TO_1.8.0.md` before starting v1.8.0 against the old server-data directory.

## Compatibility

A normal v1.8.0 multiplayer connection requires compatible application identity, multiplayer protocol 17, rules version 27, and packaged configuration fingerprint. Save format 6 is the current dedicated-server persistence format.

The published StarChem v1.7.0 release uses protocol 8 and save format 2. v1.7.0 and v1.8.0 clients and servers are intentionally incompatible at the multiplayer protocol layer. Update clients and servers together.

The build commit is diagnostic. It does not replace the explicit compatibility checks.

## TLS comes first

Remote login secrets are not sent until the TLS handshake succeeds and the presented server certificate passes StarChem's pinning check.

On a first remote connection, the client records the presented SHA-256 certificate fingerprint for that server scope. On later connections, a changed fingerprint causes login to fail closed before password or reusable sign-in material is sent. The user must verify an intentional identity change with the server operator before accepting a new certificate.

Loopback connections are treated as local-server connections and may update their local trust automatically. Remote endpoints retain the fail-closed pinning behavior.

## Password authentication

Retained commander identities use a challenge/proof flow inside the verified TLS connection. The server does not store the player's raw password.

Current v2 password handling uses two PBKDF2-HMAC-SHA256 stages. The client/server-scoped credential derivation uses **210,000 iterations** and a 256-bit result, with the verified TLS fingerprint, normalized commander name, and a 16-byte scoped salt incorporated into its derivation context. The server then protects the supplied verifier with a second PBKDF2-HMAC-SHA256 digest using **160,000 iterations** and per-account salt before retaining authentication state. These are separate derivations rather than one combined iteration count. Challenge nonces and password-provisioning salts are generated with secure random data.

Authentication material copied from a server save is not accepted as a reusable wire proof by itself. A connection must complete the live TLS-protected challenge flow.

Unknown and retained names use the authentication bootstrap path rather than exposing raw password data before TLS verification. Login and registration attempts remain subject to pre-authentication connection limits, source/name throttling, moderation, maintenance, whitelist, archive, kick, ban, and slot policy.

## Creating commander identities

### Local server / loopback

When joining `127.0.0.1` or another loopback address, an unused commander name can be created through the normal graphical sign-in dialog. The client requires password confirmation for local account creation.

### Remote server

The stock graphical JOIN dialog labels a remote connection as sign-in to an existing commander, but the current v1.8 server also accepts an unused remote commander name. `SideAJoin` routes that unused remote name through `RemoteRegistrationBridge`, which temporarily uses the existing loopback-only registration challenge and then restores the connection's real remote address after registration.

Remote registration is therefore automatic for an unused remote commander name in the current v1.8 implementation. There is no `starchem.auth.remoteRegistration` JVM property or `STARCHEM_AUTH_REMOTE_REGISTRATION` environment switch in the current source.

During the remote-registration path, the server deliberately strips any requested developer flag and developer token before invoking the registration flow. Creating a new remote commander does not grant remote developer authority. Normal admission, moderation, slot, rate-limit, and TLS/authentication protections still apply.

If a retained commander with the requested name already exists, the connection uses the normal authentication path instead of registration.

## Session resume

After successful authentication, the server may issue a random reusable session token over the verified TLS connection.

- A remembered token is returned only through a later verified TLS connection.
- The server keeps protected verifier state rather than treating save-extracted digest material as a valid session credential.
- Current and bounded previous-token state support reconnect recovery without making old tokens valid indefinitely.
- Successful resume rotates reusable state.
- Stale or invalid remembered sign-ins fall back to password authentication instead of creating a second commander identity.
- Admission and moderation rules still apply to resumed sessions.

## Remembered sign-ins

Reusable session tokens and password-derived reusable credentials are stored through the client credential vault rather than ordinary plaintext session properties when a supported secure store is available.

StarChem supports:

- Windows user-scoped DPAPI.
- macOS Keychain.
- Linux Secret Service.
- An owner-only file fallback when an operating-system credential service is unavailable.

TLS fingerprints and the random client-device identifier are separate non-secret metadata. Legacy remembered credentials are migrated out of plaintext session storage where the migration path supports it.

The lobby option **Remember sign-in on this computer** controls reusable sign-in storage. **CLEAR SIGN-INS** removes remembered multiplayer authentication material while retaining trusted TLS fingerprints and the client device identity.

## Password reset and identity recovery

Do not create a second commander name to work around a lost remembered sign-in. If resume fails, use the retained commander's password. If the password itself is lost, recovery must be handled by the server operator using the server's identity/administration workflow rather than by copying authentication fields from a save.

Archived, banned, kicked, or otherwise disallowed identities remain subject to their server-side policy even if the supplied password or session token is otherwise valid.

## Observer sign-in

The graphical lobby can request an approved observer session. Observer access requires server-side approval/invitation and remains read-only. Authentication does not grant gameplay authority: observer visibility and mutation restrictions are enforced by the server after sign-in.

## Server authentication files

The server keeps a private authentication-decoy secret beside the save:

```text
<save-name>-auth-decoy.key
```

Back it up with the complete server-data directory. Do not publish it or attach it to bug reports. Replacing the decoy key does not change a real player's password, but it changes synthetic unknown-identity material and is not part of a normal recovery procedure.

The managed TLS identity normally uses:

```text
<save-name>-tls.p12
<save-name>-tls.password
```

Both are security-sensitive and must remain together with the rest of the server backup. Read `TLS_IDENTITY_SECURITY.md` before moving, restoring, or replacing them.

A full persistent-server backup can also contain retained identity state, administration/moderation policy, observations, activity records, recovery state, saved world data, and other security-sensitive companion files. Protect the entire backup as operator data.

## Attempt limiting and connection bounds

Unauthenticated JOIN attempts are rate-limited independently from normal authenticated traffic. Pre-authentication connections are bounded before expensive per-connection work is allowed to grow without limit.

TLS and application authentication use bounded deadlines; partial frames do not extend the authentication period indefinitely. A successful password or session proof still must pass the server's normal admission and moderation policy before the connection gains a player session.
