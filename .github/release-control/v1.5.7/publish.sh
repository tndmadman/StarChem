#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(realpath "${1:?repository directory is required}")"
ARTIFACT_DIR="$(realpath "${2:?artifact directory is required}")"
NEW_SHA="${3:?validated commit SHA is required}"

cd "$REPO_DIR"
git fetch origin main --tags
test "$(git rev-parse origin/main)" = "$NEW_SHA"

if git rev-parse -q --verify refs/tags/v1.5.7 >/dev/null; then
  echo 'Tag v1.5.7 already exists; refusing to replace it.' >&2
  exit 1
fi
if gh release view v1.5.7 --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
  echo 'Release v1.5.7 already exists; refusing to replace it.' >&2
  exit 1
fi

(
  cd "$ARTIFACT_DIR"
  sha256sum -c StarChem-v1.5.7.zip.sha256
)
gh release create v1.5.7 \
  --repo "$GITHUB_REPOSITORY" \
  --target "$NEW_SHA" \
  --title 'StarChem v1.5.7' \
  --notes-file RELEASE_NOTES.md \
  "$ARTIFACT_DIR/StarChem-v1.5.7.zip" \
  "$ARTIFACT_DIR/StarChem-v1.5.7.zip.sha256"

git fetch origin --tags --force
test "$(git rev-parse 'v1.5.7^{}')" = "$NEW_SHA"

AUDIT_DIR="$RUNNER_TEMP/starchem-v157-release-audit"
rm -rf "$AUDIT_DIR"
mkdir -p "$AUDIT_DIR/download" "$AUDIT_DIR/extracted"
gh release download v1.5.7 --repo "$GITHUB_REPOSITORY" --dir "$AUDIT_DIR/download"
(
  cd "$AUDIT_DIR/download"
  sha256sum -c StarChem-v1.5.7.zip.sha256
)
unzip -q "$AUDIT_DIR/download/StarChem-v1.5.7.zip" -d "$AUDIT_DIR/extracted"
VERSION_OUTPUT="$("$AUDIT_DIR/extracted/StarChem/run-starchem.sh" --version)"
test "$VERSION_OUTPUT" = "StarChem 1.5.7 (${NEW_SHA:0:12})"
test -f "$AUDIT_DIR/extracted/StarChem/run-starchem.bat"
test -f "$AUDIT_DIR/extracted/StarChem/run-starchem-server.bat"
