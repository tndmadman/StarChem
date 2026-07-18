#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(realpath "${1:?repository directory is required}")"
cd "$REPO_DIR"

git fetch origin main --tags
git reset --hard origin/main
if git rev-parse -q --verify refs/tags/v1.5.7 >/dev/null; then
  echo 'Tag v1.5.7 already exists; refusing to replace it.' >&2
  exit 1
fi

grep -Fx '# StarChem v1.5.7' RELEASE_NOTES.md
grep -Fq 'automaticallyTrustLoopbackServer' src/main/java/com/tndmadman/rts/TlsIdentity.java
grep -Fq 'same-machine certificate replacement incorrectly required user confirmation' src/main/java/com/tndmadman/rts/NetworkSecurityValidator.java
! grep -Fq 'StarChem v1.5.6' RELEASE_NOTES.md

NEW_SHA="$(git rev-parse HEAD)"
test "$NEW_SHA" = '6e52717b1cd6158f4f619e8b7c72b20f9b051817'
echo "new_sha=$NEW_SHA" >> "$GITHUB_OUTPUT"

gradle clean check jar --no-daemon \
  -PreleaseVersion=1.5.7 \
  -PbuildCommit="$NEW_SHA"

rm -rf release
mkdir -p release/StarChem
cp build/libs/StarChem.jar release/StarChem/StarChem.jar
cp -R config release/StarChem/config
cp packaging/run-starchem.bat release/StarChem/run-starchem.bat
cp packaging/run-starchem-server.bat release/StarChem/run-starchem-server.bat
cp packaging/run-starchem.sh release/StarChem/run-starchem.sh
cp packaging/run-starchem-server.sh release/StarChem/run-starchem-server.sh
chmod +x release/StarChem/run-starchem.sh release/StarChem/run-starchem-server.sh
cp README.md release/StarChem/README.md
cp LICENSE release/StarChem/LICENSE
cp THIRD_PARTY_NOTICES.md release/StarChem/THIRD_PARTY_NOTICES.md

COMMIT_EPOCH="$(git show -s --format=%ct "$NEW_SHA")"
find release/StarChem -exec touch -d "@$COMMIT_EPOCH" {} +
(
  cd release
  mapfile -t ENTRIES < <(find StarChem -print | LC_ALL=C sort)
  printf '%s\n' "${ENTRIES[@]}" | zip -X -q StarChem-v1.5.7.zip -@
  printf '%s\n' "${ENTRIES[@]}" | zip -X -q StarChem-v1.5.7.zip.repro -@
  cmp StarChem-v1.5.7.zip StarChem-v1.5.7.zip.repro
  rm StarChem-v1.5.7.zip.repro
)
cp release/StarChem-v1.5.7.zip .
sha256sum StarChem-v1.5.7.zip > StarChem-v1.5.7.zip.sha256
sha256sum -c StarChem-v1.5.7.zip.sha256
java -jar build/libs/StarChem.jar --version | grep -Fx "StarChem 1.5.7 (${NEW_SHA:0:12})"

VERIFY_ROOT="$RUNNER_TEMP/starchem-v157-package"
rm -rf "$VERIFY_ROOT"
mkdir -p "$VERIFY_ROOT"
unzip -q StarChem-v1.5.7.zip -d "$VERIFY_ROOT"
"$VERIFY_ROOT/StarChem/run-starchem.sh" --version | grep -Fx "StarChem 1.5.7 (${NEW_SHA:0:12})"
"$VERIFY_ROOT/StarChem/run-starchem-server.sh" --help | grep -Fq -- '--server [PORT]'
