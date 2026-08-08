package com.tndmadman.rts;

import java.util.Set;

/** Deterministic coverage for radar-directed combat responses layered on issue #292 policies. */
public final class Issue292RadarCombatValidator {
    private Issue292RadarCombatValidator() { }

    public static void main(String[] args) {
        validateGuardInterceptionPreservesOrder();
        validateStanceAndThreatRules();
        validatePriorityAndOwnershipIsolation();
        validateResponseLimitAndResponderSelection();
        validateClassifiedContactInvestigation();
        validatePlayerCommandReleasesRadarControl();
        System.out.println("Issue 292 radar combat coordination validation passed.");
    }

    private static void validateGuardInterceptionPreservesOrder() {
        World world = world("Radar guard interception");
        Base radar = radar(world, "radar_picket", world.width * 0.46, world.height * 0.46, "GUARD");
        Unit guard = armedUnit(world, "SOLO", 9401, radar.x + 45, radar.y + 15);
        guard.setOrder(new UnitOrderCommand(guard.playerId, guard.unitId, UnitOrderType.GUARD,
                radar.x, radar.y, radar.x, radar.y, 170, CombatTarget.base(radar), 0));
        Unit enemy = hostileUnit(world, 9501, "destroyer", radar.x + 900, radar.y + 30);

        IntelWarfareSystem.update(world, 0.50);
        require(IntelWarfareSystem.radarResponseCount(world, radar.id) == 1,
                "radar did not dispatch its configured guard responder");
        require(CombatTarget.unit(enemy).equals(guard.attackTarget),
                "radar guard responder did not receive the identified hostile target");
        require(guard.orderType == UnitOrderType.GUARD && CombatTarget.base(radar).equals(guard.orderTarget),
                "radar interception destroyed the responder's Guard order");
        require(CombatPolicySystem.attackIntent(world, guard) == AttackIntentSource.AUTOMATIC,
                "radar interception should remain automatic combat intent");
        require(IntelWarfareSystem.radarPursuitAllowed(world, guard, enemy.x, enemy.y),
                "radar response leash did not permit interception outside the ordinary guard radius");

        new WeaponSystem().update(world, 0.05);
        require(guard.orderType == UnitOrderType.GUARD,
                "weapon pursuit cleared the preserved Guard order");

        enemy.hp = 0;
        new WeaponSystem().update(world, 0.05);
        IntelWarfareSystem.update(world, 0.50);
        require(IntelWarfareSystem.radarResponseCount(world, radar.id) == 0,
                "destroyed radar target left a stale response assignment");
        require(guard.orderType == UnitOrderType.GUARD && CombatTarget.base(radar).equals(guard.orderTarget),
                "guard responder did not retain its original assignment after interception ended");
    }

    private static void validateStanceAndThreatRules() {
        World world = world("Radar stance rules");
        Base radar = radar(world, "radar_array", world.width * 0.43, world.height * 0.48, "STANCE");
        Unit passive = armedUnit(world, "SOLO", 9402, radar.x + 40, radar.y);
        Unit hold = armedUnit(world, "SOLO", 9403, radar.x + 70, radar.y + 20);
        Unit defensive = armedUnit(world, "SOLO", 9404, radar.x + 100, radar.y - 20);
        Unit enemy = hostileUnit(world, 9502, "destroyer", radar.x + 700, radar.y);

        applyPolicy(world, passive, CombatStance.PASSIVE, TargetPriorityPolicy.NEAREST_THREAT);
        applyPolicy(world, hold, CombatStance.HOLD_FIRE, TargetPriorityPolicy.NEAREST_THREAT);
        applyPolicy(world, defensive, CombatStance.DEFENSIVE, TargetPriorityPolicy.PROTECT_ASSIGNED_TARGET);

        IntelWarfareSystem.update(world, 0.50);
        require(IntelWarfareSystem.radarResponseTarget(world, passive.key()).isBlank(),
                "Passive ship accepted an automatic radar dispatch");
        require(IntelWarfareSystem.radarResponseTarget(world, hold.key()).isBlank(),
                "Hold Fire ship accepted an automatic radar dispatch");
        require(IntelWarfareSystem.radarResponseTarget(world, defensive.key()).isBlank(),
                "Defensive ship responded to a contact that was not threatening a friendly asset");

        enemy.attackTarget = CombatTarget.base(radar);
        enemy.task = UnitTask.ATTACK;
        IntelWarfareSystem.update(world, 0.50);
        require(CombatTarget.unit(enemy).equals(IntelWarfareSystem.radarResponseTarget(world, defensive.key())),
                "Defensive ship did not respond when the radar detected an attacker threatening a friendly station");
        require(passive.attackTarget.isBlank() && hold.attackTarget.isBlank(),
                "automatic radar response bypassed Passive or Hold Fire stance");
    }

    private static void validatePriorityAndOwnershipIsolation() {
        World world = world("Radar policy targeting");
        Base radar = radar(world, "radar_array", world.width * 0.42, world.height * 0.44, "PRIORITY");
        Unit responder = armedUnit(world, "SOLO", 9405, radar.x + 30, radar.y);
        applyPolicy(world, responder, CombatStance.AGGRESSIVE, TargetPriorityPolicy.LOGISTICS_FIRST);

        Unit combat = hostileUnit(world, 9503, "destroyer", radar.x + 420, radar.y + 20);
        Unit logistics = hostileUnit(world, 9504, "hauler", radar.x + 720, radar.y - 15);
        Unit allied = armedUnit(world, "ALLY", 9601, radar.x + 15, radar.y + 45);
        IntelWarfareSystem.setIntelAlliance(world, "SOLO", "ALLY", true);

        IntelWarfareSystem.update(world, 0.50);
        require(CombatTarget.unit(logistics).equals(IntelWarfareSystem.radarResponseTarget(world, responder.key())),
                "radar dispatch ignored the responder's logistics-first target policy");
        require(IntelWarfareSystem.radarResponseTarget(world, allied.key()).isBlank(),
                "shared intel incorrectly granted the radar command authority over an allied player's ship");
        require(!CombatTarget.unit(combat).equals(responder.attackTarget),
                "radar selected the nearer combat ship over the configured logistics-first target");
    }

    private static void validateResponseLimitAndResponderSelection() {
        World world = world("Radar response limits");
        Base radar = radar(world, "radar_picket", world.width * 0.47, world.height * 0.47, "LIMIT");
        Unit close = armedUnit(world, "SOLO", 9406, radar.x + 35, radar.y);
        Unit far = armedUnit(world, "SOLO", 9407, radar.x + 210, radar.y);
        hostileUnit(world, 9505, "destroyer", radar.x + 650, radar.y + 10);

        IntelWarfareSystem.update(world, 0.50);
        require(IntelWarfareSystem.radarResponseCount(world, radar.id) == 1,
                "Radar Picket I exceeded its configured one-ship combat response limit");
        require(!IntelWarfareSystem.radarResponseTarget(world, close.key()).isBlank(),
                "radar did not choose the nearest equivalent idle responder");
        require(IntelWarfareSystem.radarResponseTarget(world, far.key()).isBlank(),
                "radar assigned more responders than its response limit allows");
    }

    private static void validateClassifiedContactInvestigation() {
        World world = world("Radar classified investigation");
        Base radar = radar(world, "radar_picket", world.width * 0.40, world.height * 0.50, "CLASSIFIED");
        Unit responder = armedUnit(world, "SOLO", 9408, radar.x + 25, radar.y);
        applyPolicy(world, responder, CombatStance.AGGRESSIVE, TargetPriorityPolicy.NEAREST_THREAT);
        Unit enemy = hostileUnit(world, 9506, "destroyer", radar.x + 900, radar.y);

        IntelWarfareSystem.DetectionStage stage = IntelWarfareSystem.unitStage(world, "SOLO", enemy);
        for (int distance = 900; distance <= 1700 && stage != IntelWarfareSystem.DetectionStage.CLASSIFIED; distance += 25) {
            enemy.x = radar.x + distance;
            enemy.y = radar.y + 35;
            stage = IntelWarfareSystem.unitStage(world, "SOLO", enemy);
        }
        require(stage == IntelWarfareSystem.DetectionStage.CLASSIFIED,
                "validator could not produce a classified-only radar contact");
        double exactX = enemy.x;
        double exactY = enemy.y;

        IntelWarfareSystem.update(world, 0.50);
        require(responder.attackTarget.isBlank(),
                "classified-only contact was converted into an illegal precise attack target");
        require(responder.task == UnitTask.MOVE,
                "idle responder did not investigate a classified radar contact");
        require(Calc.distance(responder.targetX, responder.targetY, exactX, exactY) > 0.01,
                "classified radar investigation leaked the hostile's exact coordinates");
        require(CombatTarget.unit(enemy).equals(IntelWarfareSystem.radarResponseTarget(world, responder.key())),
                "classified investigation did not retain the radar contact identity for coordination");
    }

    private static void validatePlayerCommandReleasesRadarControl() {
        World world = world("Radar player override");
        Base radar = radar(world, "radar_picket", world.width * 0.44, world.height * 0.44, "OVERRIDE");
        Unit responder = armedUnit(world, "SOLO", 9409, radar.x + 35, radar.y);
        Unit enemy = hostileUnit(world, 9507, "destroyer", radar.x + 700, radar.y + 15);

        IntelWarfareSystem.update(world, 0.50);
        require(CombatTarget.unit(enemy).equals(IntelWarfareSystem.radarResponseTarget(world, responder.key())),
                "validator setup did not create an active radar response");

        double manualX = radar.x - 240;
        double manualY = radar.y + 180;
        require(AUnitMove.apply(world, new MoveCommand(responder.playerId, responder.unitId, manualX, manualY)),
                "explicit player move setup was rejected");
        IntelWarfareSystem.update(world, 0.50);

        require(IntelWarfareSystem.radarResponseTarget(world, responder.key()).isBlank(),
                "radar reclaimed a ship after an explicit player move");
        require(responder.task == UnitTask.MOVE
                        && Calc.distance(responder.targetX, responder.targetY, manualX, manualY) < 0.01,
                "radar release altered the player's explicit move destination");
        require(responder.attackTarget.isBlank(),
                "radar reasserted its old target after the player's explicit move");
    }

    private static UnitQueueApplyResult applyPolicy(World world, Unit unit, CombatStance stance,
                                                     TargetPriorityPolicy priority) {
        UnitQueueMutation mutation = new UnitQueueMutation(unit.playerId, unit.unitId, UnitQueueOperation.POLICY,
                UnitCommandQueueSystem.revision(world, unit.key()),
                QueuedUnitCommand.policy(world.activeSystemId(), stance, priority));
        UnitQueueApplyResult result = UnitCommandQueueSystem.applyGlobal(world, mutation);
        require(result == UnitQueueApplyResult.APPLIED, "combat policy setup failed for " + unit.key());
        return result;
    }

    private static World world(String name) {
        PlayerRegistry.reset("SOLO", name, 0x50BEFF);
        World world = new World(name, Set.of(), StarSystems.DEFAULT_SYSTEM_ID);
        PlayerRegistry.activate(world);
        return world;
    }

    private static Base radar(World world, String typeId, double x, double y, String suffix) {
        Base radar = new Base("SOLO:RADAR-" + suffix, "SOLO", typeId, x, y);
        world.bases.put(radar.id, radar);
        world.saveActiveSystem();
        return radar;
    }

    private static Unit armedUnit(World world, String owner, int unitId, double x, double y) {
        Unit unit = new Unit(owner, unitId, "destroyer", x, y);
        unit.loadoutId = "destroyer_rail_escort";
        world.units.put(unit.key(), unit);
        world.saveActiveSystem();
        return unit;
    }

    private static Unit hostileUnit(World world, int unitId, String hullId, double x, double y) {
        Unit unit = new Unit(Config.CORSAIRS_ID, unitId, hullId, x, y);
        ShipLoadoutDefinition loadout = WeaponRules.findLoadout(WeaponRules.defaultLoadoutId(hullId));
        if (loadout != null) unit.loadoutId = loadout.id();
        world.units.put(unit.key(), unit);
        world.saveActiveSystem();
        return unit;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
