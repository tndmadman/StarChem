# Upgrading StarChem v1.7.0 to v1.8.0

StarChem v1.8.0 can load and migrate a server-data directory created by the published v1.7.0 release. The published v1.7.0 baseline uses multiplayer protocol 8, rules version 14, and save format 2. StarChem v1.8.0 uses protocol 17, rules version 27, and save format 6.

This is a one-way operational upgrade unless you restore the complete pre-upgrade backup. Do not expect a v1.7.0 binary to safely read state after v1.8.0 has migrated and written it.

## Before upgrading

1. Stop the v1.7.0 dedicated server cleanly.
2. Confirm the old process is no longer running and no save operation is still in progress.
3. Back up the **entire server-data directory**, not only the current `.starchem-save` file.
4. Keep that backup unchanged until the upgraded server has been verified and operated successfully.

The backup should preserve everything in the server-data directory, including current/previous saves, timestamped backups, TLS identity and password files, retained-player/session state, administration and moderation state, observations, activity/event journals, recovery state, discovered-system/intel state, and any other companion files present in the directory.

## Upgrade procedure

1. Extract StarChem v1.8.0 into a new application directory. Do not install it over the old v1.7.0 application directory.
2. Keep the original v1.7.0 program files available separately for emergency rollback with the pre-upgrade backup.
3. Start the v1.8.0 dedicated server using the same server-data directory and the same save name used by v1.7.0.
4. Allow the server to load and migrate the existing state. Do not interrupt the first successful save after migration.
5. Confirm the server reaches `Dedicated server ready.` without save, TLS, authentication, or companion-state errors.

## Verify before reopening the server

Before accepting normal remote play, check that the upgraded server still has the expected:

- retained player identities and names;
- player ships and stations in the correct systems;
- research progress;
- station and ship inventories;
- production queues and station packages;
- authored/custom ship fits and valid default fits on migrated legacy ships;
- discovered systems, tactical intelligence, and remembered wormholes where applicable;
- production policies, logistics routes, and physical cargo state where applicable;
- administration/moderation settings and observations;
- galaxy/event state;
- TLS certificate fingerprint.

Clients may need to reconnect using their normal remembered sign-in or password flow. v1.8.0 preserves the retained account/authentication state needed for migration, but v1.7.0 clients cannot connect because the multiplayer protocol has changed from 8 to 17.

## TLS identity

The server should keep the same managed TLS identity when the existing server-data directory is reused. Compare the server fingerprint with the value recorded before the upgrade. An unexpected fingerprint change should be investigated before users accept a new trust identity.

Do not delete or regenerate the TLS files merely to make startup succeed. If the identity cannot be loaded safely, restore the backup and diagnose the underlying storage/permission problem.

## Saves and rollback

The first successful v1.8.0 save rewrites current server state using save format 6. After that point, rollback is **restore-based**, not binary-based.

To roll back:

1. Stop v1.8.0 cleanly.
2. Move the upgraded server-data directory aside for diagnosis; do not merge individual upgraded files back into the old backup.
3. Restore the complete pre-upgrade v1.7.0 server-data directory as one coherent set.
4. Start the published v1.7.0 binary against that restored directory.
5. Verify the old world and TLS fingerprint before reopening remote access.

Never point v1.7.0 directly at a directory that v1.8.0 has already migrated and saved.

## Automated release validation

The v1.8.0 release gate does not rely on a hand-authored approximation of old data. CI checks out the immutable published v1.7.0 commit (`71bf62d1eb6a35e747ad9b494fded32b6e5e57fb`), asserts that historical source reports save format 2 and protocol 8, generates a real server/client fixture with that code, and then validates the upgrade using current StarChem.

The gate verifies migration and restart behavior for world/player state, inventories, research, production, legacy ship loadouts, cross-system assets, TLS identity, administration/observation/activity companion state, remembered-token resume, password reclaim, current-format resave, and a second current-code reload. The same gate runs in both normal CI and release-package validation.

Automated validation reduces upgrade risk but does not replace the required full-directory backup for a real persistent server.
