#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

fail() {
  echo "Release metadata validation failed: $*" >&2
  exit 1
}

NOTES_VERSION="$(sed -n '1s/^# StarChem v//p' RELEASE_NOTES.md | tr -d '\r')"
[[ "$NOTES_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]] \
  || fail "RELEASE_NOTES.md must begin with a semantic '# StarChem v<version>' heading"

DEV_VERSION="$(sed -n 's/^releaseVersion=//p' gradle.properties | head -n 1 | tr -d '\r')"
[[ "$DEV_VERSION" == "${NOTES_VERSION}-dev" ]] \
  || fail "gradle.properties is '$DEV_VERSION'; expected '${NOTES_VERSION}-dev'"

EXPECTED_BUILDINFO="private static final String FALLBACK_VERSION = \"${DEV_VERSION}\";"
grep -Fqx "    ${EXPECTED_BUILDINFO}" src/main/java/com/tndmadman/rts/BuildInfo.java \
  || fail "BuildInfo fallback does not match gradle.properties"

grep -Fq ".orElse('${DEV_VERSION}')" build.gradle \
  || fail "build.gradle fallback does not match gradle.properties"

PROTOCOL="$(sed -n 's/^[[:space:]]*static final int PROTOCOL_VERSION = \([0-9][0-9]*\);/\1/p' \
  src/main/java/com/tndmadman/rts/MultiplayerCompatibility.java | head -n 1)"
SAVE_FORMAT="$(sed -n 's/^[[:space:]]*static final int SAVE_FORMAT_VERSION = \([0-9][0-9]*\);/\1/p' \
  src/main/java/com/tndmadman/rts/ServerSaveStore.java | head -n 1)"
RULES_VERSION="$(sed -n 's/^[[:space:]]*\"rulesVersion\":[[:space:]]*\([0-9][0-9]*\),/\1/p' \
  config/starchem.json | head -n 1)"

[[ "$PROTOCOL" == "17" ]] || fail "v${NOTES_VERSION} expects protocol 17; source reports '$PROTOCOL'"
[[ "$RULES_VERSION" == "27" ]] || fail "v${NOTES_VERSION} expects rules version 27; config reports '$RULES_VERSION'"
[[ "$SAVE_FORMAT" == "6" ]] || fail "v${NOTES_VERSION} expects save format 6; source reports '$SAVE_FORMAT'"

grep -Fq 'protocol 17' RELEASE_NOTES.md || fail "release notes do not document protocol 17"
grep -Fq 'rules version 27' RELEASE_NOTES.md || fail "release notes do not document rules version 27"
grep -Fq 'save format 6' RELEASE_NOTES.md || fail "release notes do not document save format 6"

[[ -s UPGRADING_TO_1.8.0.md ]] || fail "UPGRADING_TO_1.8.0.md is missing or empty"
grep -Fq 'save format 2' UPGRADING_TO_1.8.0.md || fail "upgrade guide does not identify the published v1.7 save format"
grep -Fq 'save format 6' UPGRADING_TO_1.8.0.md || fail "upgrade guide does not identify the v1.8 save format"
grep -Fq 'protocol 8' UPGRADING_TO_1.8.0.md || fail "upgrade guide does not identify the published v1.7 protocol"
grep -Fq 'protocol 17' UPGRADING_TO_1.8.0.md || fail "upgrade guide does not identify the v1.8 protocol"

LEGACY_PUBLISHER=.github/workflows/publish-v1.7.0.yml
[[ -s "$LEGACY_PUBLISHER" ]] || fail "historical v1.7 publisher tombstone is missing"
grep -Fq 'Retired StarChem v1.7.0 Publisher' "$LEGACY_PUBLISHER" \
  || fail "historical v1.7 publisher was not retired"
grep -Fq 'contents: read' "$LEGACY_PUBLISHER" \
  || fail "retired v1.7 publisher does not explicitly remain read-only"
if grep -Eq 'contents:[[:space:]]*write|softprops/action-gh-release|gh[[:space:]]+release|create-release|upload-release' "$LEGACY_PUBLISHER"; then
  fail "retired v1.7 workflow still contains release-write capability"
fi

for release_doc in README.md PLAY.txt RELEASE_NOTES.md AUTHENTICATION.md TLS_IDENTITY_SECURITY.md UPGRADING_TO_1.8.0.md; do
  grep -Fq "cp $release_doc release/StarChem/$release_doc" .github/workflows/release.yml \
    || fail "release workflow does not package $release_doc"
done

bash validation/validate-release-docs.sh

echo "Release metadata validation passed for StarChem v${NOTES_VERSION}: dev=${DEV_VERSION}, protocol=${PROTOCOL}, rules=${RULES_VERSION}, save=${SAVE_FORMAT}."
