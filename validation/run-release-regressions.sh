#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <current-classpath-or-StarChem.jar>" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CP="$1"
cd "$ROOT"

# Release identity and packaging contract. Keep this first so stale version/docs/workflow
# metadata fails before the expensive historical fixture and network regressions run.
bash validation/validate-release-metadata.sh

run_java() {
  local class="$1"
  shift || true
  echo "==> $class $*"
  java -Djava.awt.headless=true -cp "$CP" "$class" "$@"
}

# Permanent regression validators that historically lived only as explicit CI steps.
run_java com.tndmadman.rts.FogOfWarValidator
run_java com.tndmadman.rts.FogPerformanceValidator
run_java com.tndmadman.rts.MovementPerformanceProfiler
run_java com.tndmadman.rts.RadarTowerValidator
run_java com.tndmadman.rts.IntelWarfareValidator
run_java com.tndmadman.rts.StationControlValidator
run_java com.tndmadman.rts.ProductionLogisticsSourcingValidator
run_java com.tndmadman.rts.ShipyardStationPackageValidator
run_java com.tndmadman.rts.MenuOverflowValidator
run_java com.tndmadman.rts.ShipyardScrollHotfixValidator
run_java com.tndmadman.rts.SessionEndpointIdentityValidator
run_java com.tndmadman.rts.NumericCommandValidationValidator authenticated
run_java com.tndmadman.rts.NumericCommandValidationValidator repair
run_java com.tndmadman.rts.NumericCommandValidationValidator serialization
run_java com.tndmadman.rts.MiningCommandValidationValidator

# Release compatibility gate: generate a real format-2 save with the published v1.7.0 code,
# load/migrate it with the current code, exercise authentication, resave, and reload it.
bash validation/run-v170-upgrade.sh "$CP"

echo "StarChem canonical release regression gate passed."
