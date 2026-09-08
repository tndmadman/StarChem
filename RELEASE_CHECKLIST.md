# StarChem v1.8.1 release checklist

This checklist is for the maintainer publishing StarChem v1.8.1. The generic `.github/workflows/release.yml` workflow remains the only publisher.

## 1. Freeze the release candidate

- Confirm `main` contains all intended v1.8.1 code and no known release-blocking issue remains open.
- Stop merging unrelated feature work while the final candidate is being validated.
- Confirm the development identity is `1.8.1-dev` before tagging; the tag-triggered workflow supplies final `1.8.1` to the release build.
- Confirm `RELEASE_NOTES.md` begins exactly with `# StarChem v1.8.1`.
- Confirm the compatibility generation remains multiplayer protocol 17, rules version 27, and save format 6.
- Confirm the multiplayer release note explicitly states that v1.8.0 and v1.8.1 still require matching application versions and therefore must be updated together.

## 2. Review release-facing documentation

Review these files together:

- `README.md`
- `PLAY.txt`
- `RELEASE_NOTES.md`
- `AUTHENTICATION.md`
- `TLS_IDENTITY_SECURITY.md`
- `UPGRADING_TO_1.8.0.md`
- `RELEASE_CHECKLIST.md`

`UPGRADING_TO_1.8.0.md` remains the migration guide for persistent servers coming from published v1.7.0 because v1.8.1 stays on the v1.8 protocol/save generation.

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

## 5. Validate v1.8.1-specific regressions

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

### v1.8.0 -> v1.8.1

- Confirm save format remains 6.
- Load representative v1.8.0 server data with the v1.8.1 candidate.
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

## 7. Tag the exact validated main commit

After every required workflow is green, identify the exact final `main` SHA and create the immutable tag:

```text
v1.8.1
```

The tag must point to the exact commit that passed final validation. Do not move or force-update an existing release tag.

## 8. Let the workflow publish

The tag-triggered `.github/workflows/release.yml` run must perform validation again. The publish job may run only after the Linux/package job and Windows-launcher job succeed.

Expected release assets:

```text
StarChem-v1.8.1.zip
StarChem-v1.8.1.zip.sha256
```

Do not manually rebuild, rename, replace, or re-upload different binaries under the same validated release identity.

## 9. Post-publish verification

After GitHub reports the release published:

- download the public ZIP and checksum from the release;
- verify the SHA-256 file against the downloaded ZIP;
- extract the ZIP into a fresh directory;
- verify `java -jar StarChem.jar --version` reports `StarChem 1.8.1` and the expected commit prefix;
- launch the Windows, Linux, or macOS player package on the intended platform;
- start a dedicated server from the packaged launcher and confirm `Dedicated server ready.`;
- confirm `java -jar StarChem.jar --help` shows the documented server/event options;
- verify F9 Manufacturing opens and can route a simple production request;
- verify narration is disabled by default and can be explicitly enabled on a supported desktop platform;
- verify a v1.8.0 client is rejected by a v1.8.1 server as an application-version mismatch rather than silently mixing versions;
- confirm the packaged release contains README, quick start, release notes, authentication/TLS guidance, v1.8 upgrade guidance, legal notices, `config/`, JAR, and platform launchers.

## 10. Roll back a bad publication safely

If post-publish verification exposes a release-blocking problem, do not silently replace assets under the same tag. Stop distribution, preserve the failed artifacts/logs, fix the repository on a new commit, rerun the complete release wall, and publish an appropriate new immutable version/tag.

For a persistent server that has already run v1.8.1, restore the complete untouched pre-update server-data backup when rolling back rather than assuming an older binary has been release-tested against files written by the newer build.

## Historical previous package identity

The previous published release was `v1.8.0` and used these immutable package names:

```text
StarChem-v1.8.0.zip
StarChem-v1.8.0.zip.sha256
```

Those names are historical only and must never be reused for the v1.8.1 publication.
