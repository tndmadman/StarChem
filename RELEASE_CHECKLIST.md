# StarChem v1.8.0 release checklist

This checklist is for the maintainer publishing the v1.8.0 release. The generic release workflow is the only current publisher.

## 1. Freeze the release candidate

- Confirm `main` contains all intended v1.8.0 code and no known release-blocking issue remains open.
- Stop merging unrelated feature work while the final release candidate is being validated.
- Confirm the development identity is `1.8.0-dev` before tagging; the tag-triggered workflow supplies final `1.8.0` to the release build.
- Confirm `RELEASE_NOTES.md` begins exactly with `# StarChem v1.8.0`.
- Confirm the compatibility contract remains multiplayer protocol 17, rules version 27, and save format 6.

## 2. Validate documentation and upgrade guidance

Review the release-facing files together:

- `README.md`
- `PLAY.txt`
- `RELEASE_NOTES.md`
- `AUTHENTICATION.md`
- `TLS_IDENTITY_SECURITY.md`
- `UPGRADING_TO_1.8.0.md`

The v1.7.0 upgrade baseline must continue to refer to the actual published v1.7.0 release: protocol 8 and save format 2. Old repository files named for v1.7 are historical tombstones and are not current operating instructions.

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

The canonical regression gate must include the real published-v1.7.0 migration fixture and current release metadata/docs checks.

Require the pull-request workflow wall to be green on the exact candidate head, including the generic **Release StarChem** workflow. That workflow must successfully:

- build and verify the release JAR;
- run canonical release regressions;
- rebuild the JAR and prove byte-identical output;
- smoke-test `StarChem.jar --version` and `--help`;
- create the deterministic release ZIP and SHA-256 file;
- validate the extracted Linux package/server;
- upload the validated package artifact;
- download that exact artifact on Windows;
- verify the checksum and both Windows launchers.

A PR run must not publish a GitHub Release.

## 4. Check persistent-server migration readiness

Before tagging, confirm the migration gate still checks out immutable published v1.7.0 commit:

```text
71bf62d1eb6a35e747ad9b494fded32b6e5e57fb
```

The gate must generate real v1.7.0 state and validate current-code migration, authentication/session recovery, TLS identity continuity, current-format resave, and current-code reload.

Do not replace this with a hand-authored approximation of an old save.

## 5. Tag the exact validated main commit

After all required workflows are green, identify the exact `main` SHA and create the immutable tag:

```text
v1.8.0
```

The tag must point to the exact commit that passed final validation. Do not move or force-update an existing release tag to a different commit.

## 6. Let the workflow publish

The tag-triggered `.github/workflows/release.yml` run must perform validation again. The publish job is allowed to run only after the Linux/package job and Windows-launcher job succeed.

Expected release assets:

```text
StarChem-v1.8.0.zip
StarChem-v1.8.0.zip.sha256
```

Do not manually rebuild, rename, replace, or re-upload different binaries under the same validated release identity.

## 7. Post-publish verification

After GitHub reports the release published:

- download the public ZIP and checksum from the release;
- verify the SHA-256 file against the downloaded ZIP;
- extract the ZIP into a fresh directory;
- verify `java -jar StarChem.jar --version` reports `StarChem 1.8.0` and the expected commit prefix;
- open the Windows or Linux player launcher on the intended platform;
- start a dedicated server from the packaged launcher and confirm `Dedicated server ready.`;
- confirm `java -jar StarChem.jar --help` shows the documented server/event options;
- confirm the packaged release contains README, release notes, authentication/TLS guidance, v1.8 upgrade guidance, legal notices, `config/`, JAR, and platform launchers.

## 8. Rollback a bad publication safely

If post-publish verification exposes a release-blocking problem, do not silently replace assets under the same tag. Stop distribution, preserve the failed artifacts/logs for diagnosis, fix the repository on a new commit, rerun the full release wall, and publish with an appropriate new immutable version/tag.

Persistent-server rollback is separate from release-publication rollback. For a server that already started v1.8.0, follow `UPGRADING_TO_1.8.0.md` and restore the complete untouched pre-upgrade server-data backup rather than pointing v1.7.0 at state already written by v1.8.0.
