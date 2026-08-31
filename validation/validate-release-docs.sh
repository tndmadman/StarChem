#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

fail() {
  echo "Release documentation validation failed: $*" >&2
  exit 1
}

require_file() {
  [[ -s "$1" ]] || fail "$1 is missing or empty"
}

require_text() {
  local file="$1"
  local text="$2"
  grep -Fq -- "$text" "$file" || fail "$file is missing required text: $text"
}

require_unstyled_text() {
  local file="$1"
  local text="$2"
  local normalized
  normalized="$(tr -d '*`' < "$file")"
  [[ "$normalized" == *"$text"* ]] \
    || fail "$file is missing required release meaning: $text"
}

reject_text() {
  local file="$1"
  local text="$2"
  if grep -Fq -- "$text" "$file"; then
    fail "$file still contains stale text: $text"
  fi
}

for file in README.md PLAY.txt RELEASE_NOTES.md AUTHENTICATION.md TLS_IDENTITY_SECURITY.md \
            UPGRADING_TO_1.8.0.md RELEASE_CHECKLIST.md CONTRIBUTING.md; do
  require_file "$file"
done

# Current public compatibility identity must agree everywhere operators are likely to look.
# Strip Markdown emphasis/code markers so presentation changes do not weaken the contract.
for file in README.md PLAY.txt AUTHENTICATION.md RELEASE_NOTES.md; do
  require_unstyled_text "$file" 'protocol 17'
  require_unstyled_text "$file" 'rules version 27'
done
for file in README.md PLAY.txt AUTHENTICATION.md RELEASE_NOTES.md UPGRADING_TO_1.8.0.md; do
  require_unstyled_text "$file" 'save format 6'
done

require_text README.md 'UPGRADING_TO_1.8.0.md'
require_text README.md 'AUTHENTICATION.md'
require_text README.md 'TLS_IDENTITY_SECURITY.md'
require_text README.md 'RELEASE_CHECKLIST.md'
require_text README.md 'run-starchem.bat'
require_text README.md './run-starchem.sh'
require_text README.md 'run-starchem-server.bat'
require_text README.md './run-starchem-server.sh'
require_text README.md '--server 50000'
require_text README.md '--join HOST 50000'
require_text README.md '--event-frequency 0..4'
require_text README.md 'Join as approved observer'
require_text README.md 'LAN server discovery'
require_text README.md '%LOCALAPPDATA%\StarChem\server'
require_text README.md '--save-dir /srv/starchem'
require_text README.md 'routes an unused remote commander name through its registration challenge automatically'

# Source-backed authentication/security guidance.
require_text AUTHENTICATION.md 'PBKDF2-HMAC-SHA256'
require_text AUTHENTICATION.md '210,000 iterations'
require_text AUTHENTICATION.md '160,000 iterations'
reject_text AUTHENTICATION.md '310,000 iterations'
require_text AUTHENTICATION.md 'Remote registration is therefore automatic'
require_text AUTHENTICATION.md 'Creating a new remote commander does not grant remote developer authority'
reject_text AUTHENTICATION.md 'starchem.auth.remoteRegistration'
reject_text AUTHENTICATION.md 'STARCHEM_AUTH_REMOTE_REGISTRATION'
require_text AUTHENTICATION.md '<save-name>-auth-decoy.key'
require_text AUTHENTICATION.md '<save-name>-tls.p12'
require_text AUTHENTICATION.md '<save-name>-tls.password'
require_text AUTHENTICATION.md 'Windows user-scoped DPAPI'
require_text AUTHENTICATION.md 'macOS Keychain'
require_text AUTHENTICATION.md 'Linux Secret Service'

require_text src/main/java/com/tndmadman/rts/PasswordAuth.java 'CLIENT_KEY_ITERATIONS = 210_000'
require_text src/main/java/com/tndmadman/rts/PasswordAuth.java 'KEY_ITERATIONS = 160_000'
require_text src/main/java/com/tndmadman/rts/SideAJoin.java 'RemoteRegistrationBridge.select(server, name, realAddress);'
require_text src/main/java/com/tndmadman/rts/SideAJoin.java 'remoteRegistration ? false : server.requestedDev(parts)'
require_text src/main/java/com/tndmadman/rts/SideAJoin.java 'remoteRegistration ? "" : server.requestedDevToken(parts)'
require_text src/main/java/com/tndmadman/rts/SideAJoin.java 'RemoteRegistrationBridge.restoreRealAddress(server, connectionId, realAddress, realPort);'
require_text src/main/java/com/tndmadman/rts/RemoteRegistrationBridge.java 'return new JoinAddress(InetAddress.getLoopbackAddress(), true);'
require_text src/main/java/com/tndmadman/rts/PeerServerSide.java 'return address != null && address.isLoopbackAddress();'
reject_text README.md 'disabled by default and should be enabled'
reject_text README.md 'starchem.auth.remoteRegistration'
reject_text README.md 'STARCHEM_AUTH_REMOTE_REGISTRATION'

# TLS operator names in the docs must be real source configuration names.
for name in starchem.tls.keystore starchem.tls.passwordFile starchem.tls.keyAlias \
            STARCHEM_TLS_KEYSTORE STARCHEM_TLS_PASSWORD_FILE STARCHEM_TLS_KEY_ALIAS; do
  require_text TLS_IDENTITY_SECURITY.md "$name"
  require_text src/main/java/com/tndmadman/rts/TlsIdentity.java "$name"
done

# Built-in help is part of the release documentation contract.
for option in '--server [PORT]' '--save-dir DIR' '--save-name NAME' '--new-world' \
              '--disable-events' '--enable-events' '--event-frequency 0..4' \
              '--event-categories LIST' '--dev-token-file FILE'; do
  require_text src/main/java/com/tndmadman/rts/App.java "$option"
done

# The parser must actually accept every release-documented startup switch.
for option in '--server' '--join' '--save-dir' '--save-name' '--new-world' '--disable-events' \
              '--enable-events' '--event-frequency' '--event-categories' '--dev-token-file'; do
  require_text src/main/java/com/tndmadman/rts/Config.java "case \"$option\""
done

# Windows packaged server storage and graphical storage override names are source-backed.
require_text packaging/run-starchem-server.bat 'STARCHEM_SERVER_SAVE_DIR'
require_text src/main/java/com/tndmadman/rts/DefaultStoragePaths.java 'starchem.saveDir'
require_text src/main/java/com/tndmadman/rts/DefaultStoragePaths.java 'STARCHEM_SAVE_DIR'

# Historical v1.7 documents must be unmistakably retired and point to the current path.
for file in UPGRADE_1.7.0.md UPGRADING_TO_1.7.0.md; do
  require_text "$file" 'Historical StarChem v1.7.0'
  require_text "$file" 'UPGRADING_TO_1.8.0.md'
  require_text "$file" 'protocol: 8'
  require_text "$file" 'save format: 2'
done

# Reject the known mutated-v1.7 documentation state that triggered Phase 3.
for file in README.md AUTHENTICATION.md UPGRADE_1.7.0.md UPGRADING_TO_1.7.0.md; do
  reject_text "$file" 'StarChem v1.7.0 uses multiplayer protocol 14'
  reject_text "$file" 'v1.7.0 uses multiplayer protocol 14'
  reject_text "$file" 'save format 5'
done
reject_text README.md 'java -jar StarChem.jar --host'
reject_text README.md 'graphical menu contains only **SOLO** and **JOIN**'

# Quick-start instructions must direct users to packaged launchers, not JAR association hacks.
require_text PLAY.txt 'run-starchem.bat'
require_text PLAY.txt './run-starchem.sh'
require_text PLAY.txt 'run-starchem-server.bat'
require_text PLAY.txt './run-starchem-server.sh'
reject_text PLAY.txt 'Choose Java Platform SE Binary'

# Maintainer release instructions must name the immutable tag and validated publishing workflow.
require_text RELEASE_CHECKLIST.md 'v1.8.0'
require_text RELEASE_CHECKLIST.md '.github/workflows/release.yml'
require_text RELEASE_CHECKLIST.md '71bf62d1eb6a35e747ad9b494fded32b6e5e57fb'
require_text RELEASE_CHECKLIST.md 'StarChem-v1.8.0.zip'
require_text RELEASE_CHECKLIST.md 'StarChem-v1.8.0.zip.sha256'

# Release package must contain the player-facing quick start and current security/upgrade docs.
for release_doc in README.md PLAY.txt RELEASE_NOTES.md AUTHENTICATION.md TLS_IDENTITY_SECURITY.md UPGRADING_TO_1.8.0.md; do
  require_text .github/workflows/release.yml "cp $release_doc release/StarChem/$release_doc"
done

echo 'Release documentation readiness validation passed.'
