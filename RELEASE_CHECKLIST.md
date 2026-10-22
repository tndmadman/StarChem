# StarChem v1.8-generation release checklist

This is the checklist for a separately authorized future StarChem release. v1.8.2 has already been published: do not recreate or move its tag. The generic `.github/workflows/release.yml` workflow remains the only publisher. Replace the `X.Y.Z` placeholders below with the newly approved version, not an existing published version.

## 1. Freeze the release candidate

- Confirm `main` contains all code intended for the new release and no known release-blocking issue remains open.
- Stop merging unrelated feature work while the final candidate is being validated.
- Set the development identity to the new approved `X.Y.Z-dev` in `gradle.properties` and `BuildInfo.java` before tagging; do not retag existing v1.8.2 builds.
- Confirm `RELEASE_NOTES.md` begins exactly with `# StarChem vX.Y.Z` for that same approved release.
- Verify the compatibility generation and document any genuine protocol, rules, or save-format change. The current v1.8.2 baseline is protocol 17, rules version 27, and save format 6; do not bump these arbitrarily.
- State that multiplayer peers require matching application versions, even when the protocol/rules/save generation remains unchanged.

## 2. Review release-facing documentation

Review these files together:

- `README.md`
- `PLAY.txt`
- `RELEASE_NOTES.md`
- `AUTHENTICATION.md`
- `TLS_IDENTITY_SECURITY.md`
- `UPGRADING_TO_1.8.0.md`
- `RELEASE_CHECKLIST.md`

`UPGRADING_TO_1.8.0.md` remains the migration guide for persistent servers coming from published v1.7.0 because published v1.8.2 stays on the v1.8 protocol/save generation.

Run:

```text
bash validation/validate-release-metadata.sh
bash validation/validate-release-docs.sh
```

## 3. Run the full verification wall

From a clean checkout of the exact candidate commit:

```text
gradle clean check jar --no-daemon
bash validation/run-release-regressions.sh 'build/classes/java/main:build/resources/main'
```

Require the normal pull-request workflow wall to be green on the exact release-candidate head.

The generic **Release StarChem** workflow must successfully:

- build and verify the release JAR;
- run canonical release regressions;
- rebuild the JAR and prove byte-identical output;
- smoke-test `StarChem.jar --version` and `--help`;
- create the deterministic release ZIP and SHA-256 file;
- validate the extracted Linux package and server;
- upload the validated package artifact;
- download that exact artifact on Windows;
- verify the checksum and both Windows launchers.

A pull-request run must not publish a GitHub Release.

## 4. Pass the v1.8 release-candidate acceptance suite

Run the existing v1.8-generation acceptance matrix against the exact candidate classes:

```text
bash validation/run-v180-rc-acceptance.sh 'build/classes/java/main:build/resources/main'
```

The matrix must continue to cover:

- clean solo and dedicated-server startup;
- simultaneous multiplayer clients, reconnect, session recovery, and persistence recovery;
- real published-v1.7.0 migration, current-format resave, restart, and authentication recovery;
- cross-system production sourcing and physical inter-system logistics;
- production policies and recovery;
- Shipyard station packages and Deployer-dependent production;
- ship fitting/refitting and atomic resource handling;
- command queues and combat/radar policies;
- dynamic event lifecycle, multiplayer isolation, and mid-event save/reload;
- wormhole connectivity, fog of war, and remote-system visibility;
- observer authority isolation;
- NPC expansion, cross-system operations, and strategic stability;
- system control, diplomacy/objective progress, and victory-condition behavior;
- clean dedicated-server shutdown/save;
- sustained TCP soak after the targeted checks.

The **StarChem v1.8 Release Candidate** workflow must be green on the pull-request head and must run successfully again on the resulting final `main` commit.

The macOS package leg must still verify the candidate checksum/build identity, player/server launchers, macOS Keychain credential storage, and packaged dedicated-server startup/shutdown.

Do not tag while the RC workflow is skipped, pending, cancelled, or red on the final `main` commit.

## 5. Validate v1.8.1/v1.8.2 regressions

Before tagging, confirm the exact candidate also passes the new/changed areas introduced after v1.8.0:

- moving-fleet fog/intel performance regression coverage;
- fleet scaling and large-selection performance validators;
- spatial render/simulation hot-path validation;
- production-policy evaluation and WAN-hotspot regression coverage;
- centralized F9 Manufacturing UI validators and production routing checks;
- narration process regression coverage;
- Windows System.Speech/narration validation.

Manual release acceptance should include a real WAN host/client check with production policies enabled and a moving-fleet comparison representative of issue #372.

## 6. Check persistent-server readiness

### Published v1.8.0/v1.8.1/v1.8.2 -> new candidate

- Verify the save format and any required migration against the actual candidate; current published v1.8.2 uses format 6.
- Load representative saved servers from the supported v1.8 releases with the new candidate.
- Verify players, inventory, research, production queues/policies, fog/intel state, sessions, TLS identity, and companion state remain intact.
- Resave and restart under the same candidate.
- Keep a complete untouched backup of the pre-update server-data directory for rollback.

### v1.7.0 -> current v1.8 generation

Confirm the migration gate still checks out immutable published v1.7.0 commit:

```text
71bf62d1eb6a35e747ad9b494fded32b6e5e57fb
```

The gate must generate real v1.7.0 state and validate current-code migration, authentication/session recovery, TLS identity continuity, current-format resave, and current-code reload.

Do not replace this with a hand-authored approximation of an old save.

### Stabilization sprint verification before the next release

- Run `gradle validateWorldLifecycle validateGalaxyInactiveSchedulerRoster --no-daemon` and `com.tndmadman.rts.CelestialResourceBridgeValidator`; these also belong to the canonical regression wall.
- Check system removal, repeated match creation, save restoration and client reconnect against the actual target branch, not merely an old merge.
- Retain the paired 33-case render performance gate with the 20% p95 threshold and review its six CSV artifacts.
- Collect F4 full-client measurements under dense resource fields, large fleets, camera pan/zoom and fog, and manually complete planetary extraction charge, impact, debris, exhaustion, cooldown and refire.
- Perform multiplayer fog/observer/security and dedicated-server shutdown/restart acceptance on real client/server instances.
- Treat unrun GUI, audio or WAN cases as release outstanding until independently executed and recorded. These changes are unreleased until an authorized future version is published.

## 7. Tag the exact validated main commit

After every required workflow is green, identify the exact final `main` SHA and create the immutable tag:

```text
vX.Y.Z
```

Use only the unused, separately authorized version; do not recreate `v1.8.2`. The tag must point to the exact commit that passed final validation. Do not move or force-update an existing release tag.

## 8. Let the workflow publish

The tag-triggered `.github/workflows/release.yml` run must perform validation again. The publish job may run only after the Linux/package job and Windows-launcher job succeed.

Expected release assets:

```text
StarChem-vX.Y.Z.zip
StarChem-vX.Y.Z.zip.sha256
```

Do not manually rebuild, rename, replace, or re-upload different binaries under the same validated release identity.

## 9. Post-publish verification

After GitHub reports the release published:

- download the public ZIP and checksum from the release;
- verify the SHA-256 file against the downloaded ZIP;
- extract the ZIP into a fresh directory;
- verify `java -jar StarChem.jar --version` reports the authorized new version and expected commit prefix;
- launch the Windows, Linux, or macOS player package on the intended platform;
- start a dedicated server from the packaged launcher and confirm `Dedicated server ready.`;
- confirm `java -jar StarChem.jar --help` shows the documented server/event options;
- verify F9 Manufacturing opens and can route a simple production request;
- verify narration is disabled by default and can be explicitly enabled on a supported desktop platform;
- verify an older-version client is rejected by the new-version server as an application-version mismatch rather than silently mixing versions;
- confirm the packaged release contains README, quick start, release notes, authentication/TLS guidance, v1.8 upgrade guidance, legal notices, `config/`, JAR, and platform launchers.

## 10. Roll back a bad publication safely

If post-publish verification exposes a release-blocking problem, do not silently replace assets under the same tag. Stop distribution, preserve the failed artifacts/logs, fix the repository on a new commit, rerun the complete release wall, and publish an appropriate new immutable version/tag.

For a persistent server that has already run the newer version, restore the complete untouched pre-update server-data backup when rolling back rather than assuming an older binary has been release-tested against files written by the newer build.

## Historical v1.8.0 package identity

The original published v1.8.0 package used these immutable names; retain this history for the release-documentation gate:

```text
StarChem-v1.8.0.zip
StarChem-v1.8.0.zip.sha256
```

Those names are historical only and must never be reused for the v1.8.1 publication.
