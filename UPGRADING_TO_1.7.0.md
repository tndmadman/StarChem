# Historical StarChem v1.7.0 upgrade guide

This document is retained only to prevent old repository links from becoming ambiguous. It is **not** the current server-upgrade procedure.

The former contents described an unreleased development state under the v1.7.0 name and therefore contained protocol/save values that do not match the actual published v1.7.0 release.

For the supported persistent-server upgrade from the published StarChem v1.7.0 release to StarChem v1.8.0, read and follow:

`UPGRADING_TO_1.8.0.md`

Current supported migration baseline:

- published v1.7.0 multiplayer protocol: 8
- published v1.7.0 save format: 2
- StarChem v1.8.0 multiplayer protocol: 17
- StarChem v1.8.0 rules version: 27
- StarChem v1.8.0 save format: 6

The v1.8.0 release gate validates this path from an immutable published-v1.7.0 fixture through current migration, authentication/session recovery, TLS identity continuity, current-format resave, and restart.

Do not operate a current server from instructions copied from the former contents of this historical file.
