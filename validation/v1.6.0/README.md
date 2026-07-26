# StarChem v1.6.0 upgrade fixture

The release gate generates its fixture from the immutable published `v1.6.0` tag at commit `e83df3158d583b8036a80ee9cd1e866cfc2491e3`.

`V160FixtureGenerator.java` is copied into a detached worktree of that commit and compiled against the actual v1.6.0 code. It creates a synthetic directory containing:

- current, previous, and timestamped format-2 server saves;
- two retained password identities with current and expired previous resume tokens;
- completed research, ships and stations, inventories, an active production queue, player-home ownership, cross-system assets, and NPC/runtime state;
- the v1.6.0 managed TLS keystore with its original shared password, secured for safe CI storage;
- administration, moderation, observation, and activity-journal companions supported by v1.6.0;
- a v1.6.0 `sessions.properties` file containing test-only TLS trust and remembered sign-ins.

All names, passwords, tokens, addresses, and keys are generated solely for validation. They are not copied from a real server. The validator copies the generated fixture before upgrade and proves the source tree is unchanged.

The normal `check` task runs this gate on Linux CI through `SavedCredentialReplayValidator`. It can also be run manually after compiling current sources:

```bash
bash validation/run-v160-upgrade.sh 'build/classes/java/main:build/resources/main'
```
