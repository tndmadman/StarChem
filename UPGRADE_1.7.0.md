# Historical StarChem v1.7.0 upgrade note

This file is retained only as a historical repository marker. It is **not** the current upgrade procedure and its former development-era protocol/save descriptions no longer describe a published release accurately.

For a persistent server running the published StarChem v1.7.0 release and upgrading to StarChem v1.8.0, use:

`UPGRADING_TO_1.8.0.md`

The v1.8.0 guide is backed by the release migration gate, which checks out the immutable published v1.7.0 source commit, creates real v1.7.0 server/client state, migrates it with current code, resaves it, and reloads it.

Published baseline for that supported upgrade path:

- v1.7.0 multiplayer protocol: 8
- v1.7.0 save format: 2
- v1.8.0 multiplayer protocol: 17
- v1.8.0 rules version: 27
- v1.8.0 save format: 6

Do not use old development-era v1.7 instructions to operate or roll back a v1.8 server.
