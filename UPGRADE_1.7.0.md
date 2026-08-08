# Upgrading StarChem v1.6.0 servers to v1.7.0

StarChem v1.7.0 uses multiplayer protocol 14, rules version 27, and save format 5.

## Before upgrading

Stop the v1.6.0 server cleanly and make an offline copy of the entire server save directory. Keep the current save, previous save, every timestamped backup, the `*-tls.p12` identity, and all administration, moderation, observation, and activity files together.

Also back up each client profile's `.starchem/sessions.properties` file before launching v1.7.0. The server save alone is not a complete rollback backup.

## First v1.7.0 start

Start v1.7.0 with the same save directory and save name. The upgrade migrates retained world and player state to save format 5 and migrates related identity storage in place:

- The existing TLS certificate is retained, so its SHA-256 fingerprint must not change.
- The managed `*-tls.p12` file is re-protected with a generated owner-only `*-tls.password` file.
- Existing v1.6.0 password identities remain reclaimable. A successful reclaim upgrades that identity to the server-scoped v1.7.0 password representation.
- Existing current resume tokens may reconnect and are rotated on use. A frozen previous token from an old backup is normally expired; only the freshly replaced token receives the short v1.7.0 grace window.
- Remembered client tokens and password-equivalent values are removed from plaintext `sessions.properties` storage and moved into the protected credential vault.
- v1.7.0 creates identity-lifecycle and recovery companion files beside the save where required.
- Ships, production jobs, and refit jobs are migrated to authoritative loadout references; current saves retain the exact runtime fit catalog and reserved refit materials.

Verify the server log reports the existing save loaded, then verify the expected players, research, ships, fitted loadouts, stations, inventories, production queues, active refits, controlled systems, and remote systems before allowing normal play.

## Rollback limits

Do not run a v1.6.0 binary against a directory that v1.7.0 has already upgraded.

After v1.7.0 starts:

- v1.6.0 cannot open the re-protected TLS keystore because it does not understand `*-tls.password`;
- password identities successfully reclaimed by v1.7.0 may use a representation v1.6.0 cannot authenticate;
- v1.6.0 clients cannot read v1.7.0 credential-vault markers as remembered tokens or password authenticators;
- v1.6.0 does not understand new v1.7.0 identity, recovery, and previous-state companion files;
- v1.6.0 does not understand save format 5 or authoritative runtime fit data;
- v1.6.0 protocol 7 and v1.7.0 protocol 14 are intentionally incompatible.

To roll back, stop v1.7.0 and restore the complete pre-upgrade server directory and the pre-upgrade client credential files. Restoring only one `.starchem-save` archive is not sufficient.
