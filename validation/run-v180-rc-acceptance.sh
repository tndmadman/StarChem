#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <current-classpath-or-StarChem.jar>" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CP="$1"
cd "$ROOT"

section() {
  printf '\n===== v1.8 RC: %s =====\n' "$1"
}

run_java() {
  local class="$1"
  shift || true
  echo "==> $class $*"
  java -Djava.awt.headless=true -cp "$CP" "$class" "$@"
}

section "release identity and operator documentation"
bash validation/validate-release-metadata.sh
bash validation/validate-release-docs.sh

section "clean solo and dedicated-server startup"
run_java com.tndmadman.rts.GalaxyConnectivityValidator
run_java com.tndmadman.rts.DedicatedTcpServerValidator

section "two-client authority, reconnect, and persistence recovery"
run_java com.tndmadman.rts.TcpMultiClientValidator
run_java com.tndmadman.rts.TcpReconnectIntegrationValidator
run_java com.tndmadman.rts.SessionRecoveryValidator
run_java com.tndmadman.rts.ServerSaveStoreValidator
run_java com.tndmadman.rts.ServerPersistenceCoordinatorValidator

section "cross-system production and physical inter-system logistics"
run_java com.tndmadman.rts.ProductionLogisticsSourcingValidator
run_java com.tndmadman.rts.Issue293LogisticsRouteValidator

section "production policies and shipyard station-package deployment"
run_java com.tndmadman.rts.Issue294ProductionPolicyValidator
run_java com.tndmadman.rts.Issue294ProductionPolicyRecoveryValidator
run_java com.tndmadman.rts.ShipyardStationPackageValidator
run_java com.tndmadman.rts.ProductionQueueValidator

section "ship construction, fitting, refit, and atomic resource handling"
run_java com.tndmadman.rts.CustomFitConstructionValidator
run_java com.tndmadman.rts.ShipModuleRefitValidator
run_java com.tndmadman.rts.RefitQueuePlannerValidator
run_java com.tndmadman.rts.AtomicRefitTransactionValidator

section "command queues and combat policies"
run_java com.tndmadman.rts.Issue291CommandQueueValidator
run_java com.tndmadman.rts.Issue292CombatPolicyValidator
run_java com.tndmadman.rts.Issue292RadarCombatValidator

section "dynamic galaxy event completion, multiplayer, and save/reload"
run_java com.tndmadman.rts.GalaxyEventValidator
run_java com.tndmadman.rts.GalaxyEventPersistenceValidator
run_java com.tndmadman.rts.GalaxyEventMultiplayerValidator

section "wormholes, fog of war, and remote-system visibility"
run_java com.tndmadman.rts.AuthoritativeFogOfWarValidator
run_java com.tndmadman.rts.TcpRemoteSystemVisibilityValidator

section "observer authority isolation"
run_java com.tndmadman.rts.ObserverSessionValidator

section "NPC expansion, cross-system operations, and strategic stability"
run_java com.tndmadman.rts.NpcExpeditionValidator
run_java com.tndmadman.rts.NpcCrossSystemOperationsValidator
run_java com.tndmadman.rts.NpcStrategicDirectorValidator
run_java com.tndmadman.rts.NpcStrategicStabilityValidator

section "system control, diplomacy, and victory objectives"
run_java com.tndmadman.rts.SystemControlValidator
run_java com.tndmadman.rts.DiplomacyObjectiveValidator
run_java com.tndmadman.rts.ObjectiveSystemValidator

section "clean dedicated-server shutdown and save"
run_java com.tndmadman.rts.ServerShutdownValidator

section "short sustained TCP soak"
java -Djava.awt.headless=true -Dstarchem.soakSeconds=30 -cp "$CP" com.tndmadman.rts.TcpSoakValidator

section "published v1.7.0 migration, resave, restart, and authentication recovery"
bash validation/run-v170-upgrade.sh "$CP"

printf '\nStarChem v1.8.0 release-candidate acceptance suite passed.\n'
