#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <current-classpath-or-StarChem.jar>" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CP="$1"
cd "$ROOT"

run_java() {
  local class="$1"
  shift || true
  echo "==> $class $*"
  java -Djava.awt.headless=true -cp "$CP" "$class" "$@"
}

echo "==> GAME-STATE AUTHORITY (#395 + #397)"
run_java com.tndmadman.rts.Issue395RespawnAuthorityValidator
run_java com.tndmadman.rts.ObserverSessionValidator
run_java com.tndmadman.rts.GalaxyEventMultiplayerValidator

echo "==> PRE-AUTH IDENTITY / RESOURCE ABUSE (#402 + #404)"
run_java com.tndmadman.rts.AuthenticationEnumerationValidator
run_java com.tndmadman.rts.NetworkSecurityValidator

echo "==> SESSION / TLS IDENTITY (#400 + #406)"
run_java com.tndmadman.rts.SessionRecoveryValidator
run_java com.tndmadman.rts.PreviousTokenProofRecoveryValidator
run_java com.tndmadman.rts.TlsFirstUseTrustValidator

echo "==> LEGITIMATE MULTIPLAYER REGRESSION FLOWS"
run_java com.tndmadman.rts.TcpMultiplayerValidator
run_java com.tndmadman.rts.TcpReconnectIntegrationValidator
run_java com.tndmadman.rts.SessionEndpointIdentityValidator

echo "StarChem combined security integration validation passed."
