#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <current-classpath-or-StarChem.jar>" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CURRENT_CP="$1"
GENERATOR="$ROOT/validation/v1.7.0/V170FixtureGenerator.java"

[[ -f "$GENERATOR" ]] || { echo "v1.7.0 fixture generator is missing: $GENERATOR" >&2; exit 1; }
command -v git >/dev/null
command -v gradle >/dev/null
command -v java >/dev/null

if ! git -C "$ROOT" rev-parse --verify 'refs/tags/v1.7.0^{commit}' >/dev/null 2>&1; then
  git -C "$ROOT" fetch --force origin 'refs/tags/v1.7.0:refs/tags/v1.7.0'
fi
git -C "$ROOT" rev-parse --verify 'refs/tags/v1.7.0^{commit}' >/dev/null
SOURCE_SHA="$(git -C "$ROOT" rev-parse 'refs/tags/v1.7.0^{commit}')"
EXPECTED_SHA="71bf62d1eb6a35e747ad9b494fded32b6e5e57fb"
if [[ "$SOURCE_SHA" != "$EXPECTED_SHA" ]]; then
  echo "v1.7.0 tag resolved to $SOURCE_SHA instead of $EXPECTED_SHA" >&2
  exit 1
fi

TMP="$(mktemp -d)"
OLD="$TMP/starchem-v1.7.0"
FIXTURE="$TMP/fixture"
cleanup() {
  git -C "$ROOT" worktree remove --force "$OLD" >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT

git -C "$ROOT" worktree add --detach "$OLD" "$EXPECTED_SHA" >/dev/null
cp "$GENERATOR" "$OLD/src/main/java/com/tndmadman/rts/V170FixtureGenerator.java"

gradle -p "$OLD" classes --no-daemon
(
  cd "$OLD"
  java -Djava.awt.headless=true \
    -cp 'build/classes/java/main:build/resources/main' \
    com.tndmadman.rts.V170FixtureGenerator "$FIXTURE"
)

java -Djava.awt.headless=true -cp "$CURRENT_CP" \
  com.tndmadman.rts.V170UpgradeValidator "$FIXTURE"
