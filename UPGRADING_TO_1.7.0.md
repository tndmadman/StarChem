# Upgrading StarChem v1.6.0 to v1.7.0

StarChem v1.7.0 changes multiplayer protocol, authentication material, remembered client credentials, TLS identity protection, persistence scheduling, retained identity state, authoritative ship fits, fleet-control metadata, and several server companion files. Treat the upgrade as a server-data migration to multiplayer protocol 14, rules version 27, and save format 5.

## Before upgrading

1. Stop the v1.6.0 server cleanly.
2. Confirm the final save completed successfully.
3. Copy the complete server-data directory to a separate backup location.
4. Keep the backup read-only while validating v1.7.0.
5. Record the existing server TLS fingerprint with `server-info tls` or from a trusted client.

Do not back up only the newest `.starchem-save` file. The full directory may contain:

- Current and previous `.starchem-save` archives.
- Timestamped backup archives and checksum metadata.
- `<save-name>-tls.p12`.
- `<save-name>-tls.password` or the separately configured TLS password file.
- Administration, moderation, observation, activity-journal, recovery, and retained-identity files.
- `<save-name>-auth-decoy.key`.
- Temporary, previous, or lock files required for verified recovery.

Both the managed TLS keystore and its password are security-sensitive. Losing either can prevent the original server identity from loading. Replacing the identity changes the fingerprint clients have pinned.

## Application installation

Extract the complete v1.7.0 ZIP into a new application directory. Do not overwrite the v1.6.0 application folder in place.

The release directory must keep these items together:

- `StarChem.jar`
- `config/`
- Player and dedicated-server launchers
- `README.md`
- `RELEASE_NOTES.md`
- `AUTHENTICATION.md`
- `TLS_IDENTITY_SECURITY.md`
- `UPGRADING_TO_1.7.0.md`
- License and third-party notices

## Server upgrade

Start v1.7.0 with the same server-data directory and save name used by v1.6.0. The first successful load migrates supported legacy state to save format 5. Current saves preserve authoritative runtime fit definitions, installed loadout IDs, production selections, and exact active-refit reservations.

### Windows packaged launcher

The v1.7.0 dedicated-server launcher defaults to:

```text
%LOCALAPPDATA%\StarChem\server
```

Set `STARCHEM_SERVER_SAVE_DIR` when the existing v1.6.0 server used another location:

```text
set STARCHEM_SERVER_SAVE_DIR=D:\StarChemServer
run-starchem-server.bat
```

### Linux packaged launcher

Pass the existing save-directory option or the same environment configuration previously used by the server:

```text
./run-starchem-server.sh --save-dir /srv/starchem
```

Use the same save name. Do not create a new empty world and then copy selected files into it.

## Required validation before reopening the server

Confirm all of the following before allowing normal remote joins:

- The server reports `StarChem 1.7.0` and the expected build commit.
- The expected galaxy and system count loaded.
- Retained identities, names, archive state, and last-seen state are present.
- Player ships, installed loadouts, bases, inventories, research, production queues, active refits, ownership, and controlled systems are present.
- NPC faction runtime and cross-system state are present.
- Administration, moderation, whitelist, bans, observations, and activity history loaded as expected.
- The TLS fingerprint exactly matches the recorded v1.6.0 fingerprint.
- A known player can authenticate with the existing password.
- Reconnect and session-resume behavior works after a normal disconnect.
- A manual save succeeds, the server stops cleanly, and the saved world loads again after restart.

If any of these checks fail, stop the v1.7.0 server before it writes additional state and investigate using a fresh copy of the original backup.

## Client remembered-sign-in migration

v1.7.0 moves reusable authentication material out of ordinary `sessions.properties` storage and into the operating-system credential service when available:

- Windows user-scoped DPAPI.
- macOS Keychain.
- Linux Secret Service.
- An owner-only file fallback when a credential service is unavailable.

The TLS trust fingerprint and client device identifier remain separate non-secret metadata. Existing plaintext remembered credentials are migrated and removed from current, previous, temporary, recovery, and lock-file paths where supported.

A player may be asked to enter the password again when a remembered sign-in cannot be resumed. This does not create a new identity or discard existing assets.

## Multiplayer compatibility

v1.7.0 uses multiplayer protocol 14 and rules version 27. v1.6.0 uses protocol 7.

- A v1.6.0 client cannot join a v1.7.0 server.
- A v1.7.0 client cannot join a v1.6.0 server.
- Update clients and servers together.
- Matching build commits are not required when application version, protocol, rules version, and packaged configuration fingerprint match.
- Save format 5 is a persistence contract and is not readable by v1.6.0.

## Rollback

The only supported rollback starting point is the untouched full-directory backup made before v1.7.0 was started.

Do not point a v1.6.0 binary at a server directory after v1.7.0 has migrated or written it. v1.7.0 may create or update authentication, TLS-password, identity-lifecycle, moderation-observation, recovery, persistence, and runtime-fit state that v1.6.0 does not understand.

To roll back:

1. Stop v1.7.0.
2. Preserve the failed v1.7.0 directory separately for diagnostics.
3. Restore a fresh copy of the untouched v1.6.0 backup.
4. Start the v1.6.0 application against that restored copy.
5. Verify the world and TLS fingerprint before reopening remote access.

Never merge selected v1.7.0 companion files into the restored v1.6.0 directory.

## Backup security

A complete server backup can contain reusable security material, including the TLS private key, TLS password, authentication-decoy key, moderation data, player observations, and retained identity state. Store backups with access limited to the server operator and do not attach them to public bug reports.
