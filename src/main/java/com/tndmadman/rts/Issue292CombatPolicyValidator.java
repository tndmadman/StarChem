package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Focused deterministic coverage for issue #292 combat stances and target priorities. */
public final class Issue292CombatPolicyValidator {
    private Issue292CombatPolicyValidator() { }

    public static void main(String[] args) {
        validatePassiveAndExplicitOverride();
        validateDefensiveGuardLeash();
        validatePriorityPolicies();
        validateHoldFireAndPointDefense();
        validateHostileFilteringAndMalformedMutations();
        validateRapidPolicyChanges();
        validateWireRoundTripAndOwnerIsolation();
        validateGalaxySaveRestore();
        validateWormholeContinuity();
        System.out.println("Issue 292 combat policy validation passed.");
    }

    private static void validatePassiveAndExplicitOverride() {
        World world = world("Combat policy passive");
        Unit attacker = armedSoloUnit(world, 9201, world.width * 0.45, world.height * 0.48);
        Unit target = hostileDestroyer(world, 9301, attacker.x + 110, attacker.y + 10);

        require(applyPolicy(world, attacker, CombatStance.PASSIVE, TargetPriorityPolicy.NEAREST_THREAT)
                        == UnitQueueApplyResult.APPLIED,
                "passive stance should apply");
        new WeaponSystem().update(world, 0.05);
        require(attacker.attackTarget.isBlank(), "passive ship automatically acquired a target");

        CombatPolicySystem.markExplicitAttack(world, attacker);
        attacker.issueAttack(CombatTarget.unit(target));
        require(CombatPolicySystem.attackIntent(world, attacker) == AttackIntentSource.EXPLICIT,
                "manual attack should be explicit combat intent");
        new WeaponSystem().update(world, 0.05);
        require(CombatTarget.unit(target).equals(attacker.attackTarget),
                "passive stance blocked an explicit attack override");

        target.hp = 0;
        new WeaponSystem().update(world, 0.05);
        require(attacker.attackTarget.isBlank() && attacker.task != UnitTask.ATTACK,
                "destroyed explicit target left stale attack state");
        require(CombatPolicySystem.stance(world, attacker) == CombatStance.PASSIVE,
                "explicit attack completion did not return to configured passive stance");
        require(CombatPolicySystem.attackIntent(world, attacker) == AttackIntentSource.NONE,
                "explicit attack completion left stale intent metadata");
    }

    private static void validateDefensiveGuardLeash() {
        World world = world("Combat policy defensive leash");
        Unit guard = armedSoloUnit(world, 9202, world.width * 0.45, world.height * 0.48);
        require(applyPolicy(world, guard, CombatStance.DEFENSIVE, TargetPriorityPolicy.PROTECT_ASSIGNED_TARGET)
                        == UnitQueueApplyResult.APPLIED,
                "defensive stance should apply");
        guard.setOrder(new UnitOrderCommand(guard.playerId, guard.unitId, UnitOrderType.GUARD,
                guard.x, guard.y, guard.x, guard.y, 180, "", 0));

        Unit far = hostileDestroyer(world, 9302, guard.x + 360, guard.y);
        require(Double.isInfinite(CombatPolicySystem.scoreTarget(world, guard, CombatTarget.unit(far))),
                "defensive guard policy accepted a target outside the guard leash");

        Unit near = hostileDestroyer(world, 9303, guard.x + 95, guard.y + 20);
        near.attackTarget = CombatTarget.unit(guard);
        near.task = UnitTask.ATTACK;
        require(Double.isFinite(CombatPolicySystem.scoreTarget(world, guard, CombatTarget.unit(near))),
                "defensive guard policy rejected an immediate threat inside the leash");
    }

    private static void validatePriorityPolicies() {
        World world = world("Combat target priorities");
        Unit attacker = armedSoloUnit(world, 9203, world.width * 0.45, world.height * 0.48);
        Unit combat = hostileDestroyer(world, 9304, attacker.x + 180, attacker.y);
        Unit worker = hostileUnit(world, 9305, "hauler", attacker.x + 90, attacker.y + 20);

        require(applyPolicy(world, attacker, CombatStance.AGGRESSIVE, TargetPriorityPolicy.COMBAT_FIRST)
                        == UnitQueueApplyResult.APPLIED,
                "combat-first policy should apply");
        double combatFirstCombat = CombatPolicySystem.scoreTarget(world, attacker, CombatTarget.unit(combat));
        double combatFirstWorker = CombatPolicySystem.scoreTarget(world, attacker, CombatTarget.unit(worker));
        require(combatFirstCombat < combatFirstWorker,
                "combat-first policy did not prioritize an armed combat ship");

        require(applyPolicy(world, attacker, null, TargetPriorityPolicy.LOGISTICS_FIRST)
                        == UnitQueueApplyResult.APPLIED,
                "logistics-first policy should apply");
        double logisticsCombat = CombatPolicySystem.scoreTarget(world, attacker, CombatTarget.unit(combat));
        double logisticsWorker = CombatPolicySystem.scoreTarget(world, attacker, CombatTarget.unit(worker));
        require(logisticsWorker < logisticsCombat,
                "logistics-first policy did not prioritize a worker/logistics ship");

        Base hostileBase = new Base("CORSAIR:POLICY-BASE", Config.CORSAIRS_ID, Rules.DEFAULT_BASE,
                attacker.x + 130, attacker.y - 25);
        world.bases.put(hostileBase.id, hostileBase);
        require(applyPolicy(world, attacker, null, TargetPriorityPolicy.STRUCTURES_FIRST)
                        == UnitQueueApplyResult.APPLIED,
                "structures-first policy should apply");
        require(CombatPolicySystem.scoreTarget(world, attacker, CombatTarget.base(hostileBase))
                        < CombatPolicySystem.scoreTarget(world, attacker, CombatTarget.unit(combat)),
                "structures-first did not rank a structure ahead of a combat ship");
        require(applyPolicy(world, attacker, null, TargetPriorityPolicy.STRUCTURES_LAST)
                        == UnitQueueApplyResult.APPLIED,
                "structures-last policy should apply");
        require(CombatPolicySystem.scoreTarget(world, attacker, CombatTarget.base(hostileBase))
                        > CombatPolicySystem.scoreTarget(world, attacker, CombatTarget.unit(combat)),
                "structures-last did not rank a structure behind a combat ship");
    }

    private static void validateHoldFireAndPointDefense() {
        World world = world("Combat hold fire");
        Unit attacker = armedSoloUnit(world, 9204, world.width * 0.45, world.height * 0.48);
        Unit target = hostileDestroyer(world, 9306, attacker.x + 90, attacker.y);
        CombatPolicySystem.markExplicitAttack(world, attacker);
        attacker.issueAttack(CombatTarget.unit(target));
        require(applyPolicy(world, attacker, CombatStance.HOLD_FIRE, TargetPriorityPolicy.NEAREST_THREAT)
                        == UnitQueueApplyResult.APPLIED,
                "hold-fire stance should apply");
        double before = target.hp;
        WeaponSystem weapons = new WeaponSystem();
        for (int i = 0; i < 30; i++) weapons.update(world, 0.05);
        require(Math.abs(target.hp - before) < 0.000001,
                "hold-fire ship dealt offensive damage");
        require(CombatTarget.unit(target).equals(attacker.attackTarget),
                "hold fire should retain an explicit target for release");

        Unit screen = pointDefenseSoloUnit(world, 9205, attacker.x + 260, attacker.y + 20);
        require(applyPolicy(world, screen, CombatStance.HOLD_FIRE, TargetPriorityPolicy.SCREENING)
                        == UnitQueueApplyResult.APPLIED,
                "screening hold-fire policy should apply");
        ProjectileShot incoming = new ProjectileShot(99001, Config.CORSAIRS_ID, "light_missile",
                CombatTarget.unit(screen), screen.x + 40, screen.y);
        incoming.lastX = incoming.x;
        incoming.lastY = incoming.y;
        world.shots.add(incoming);
        weapons.update(world, 0.01);
        require(!world.shots.contains(incoming),
                "point defense did not intercept a hostile stoppable projectile under hold fire");
    }

    private static void validateHostileFilteringAndMalformedMutations() {
        World world = world("Combat policy security");
        Unit attacker = armedSoloUnit(world, 9206, world.width * 0.45, world.height * 0.48);
        Unit friendly = new Unit("SOLO", 9207, "destroyer", attacker.x + 80, attacker.y);
        friendly.loadoutId = "destroyer_rail_escort";
        world.units.put(friendly.key(), friendly);
        require(Double.isInfinite(CombatPolicySystem.scoreTarget(world, attacker, CombatTarget.unit(friendly))),
                "target policy admitted a friendly unit");

        UnitQueueMutation wrongOwner = new UnitQueueMutation("OTHER", attacker.unitId, UnitQueueOperation.POLICY, 0,
                QueuedUnitCommand.policy(world.activeSystemId(), CombatStance.PASSIVE, null));
        require(UnitCommandQueueSystem.applyGlobal(world, wrongOwner) == UnitQueueApplyResult.REJECTED,
                "policy mutation accepted an unauthorized owner/unit pair");

        QueuedUnitCommand malformed = new QueuedUnitCommand(0, QueuedCommandKind.TACTICAL, world.activeSystemId(),
                0, 0, 0, 0, 0, "NOT_A_STANCE", -1, "", "NOT_A_PRIORITY", UnitOrderType.NONE);
        UnitQueueMutation invalidEnums = new UnitQueueMutation(attacker.playerId, attacker.unitId,
                UnitQueueOperation.POLICY, UnitCommandQueueSystem.revision(world, attacker.key()), malformed);
        require(UnitCommandQueueSystem.applyGlobal(world, invalidEnums) == UnitQueueApplyResult.REJECTED,
                "policy mutation accepted malformed enum values");
    }

    private static void validateRapidPolicyChanges() {
        World world = world("Combat rapid changes");
        Unit unit = armedSoloUnit(world, 9208, world.width * 0.45, world.height * 0.48);
        CombatStance[] sequence = {
                CombatStance.PASSIVE,
                CombatStance.DEFENSIVE,
                CombatStance.HOLD_FIRE,
                CombatStance.AGGRESSIVE,
                CombatStance.PASSIVE
        };
        for (CombatStance stance : sequence) {
            require(applyPolicy(world, unit, stance, null) == UnitQueueApplyResult.APPLIED,
                    "rapid policy mutation failed for " + stance);
        }
        require(CombatPolicySystem.stance(world, unit) == CombatStance.PASSIVE,
                "rapid policy changes did not converge to the final authoritative stance");

        long current = UnitCommandQueueSystem.revision(world, unit.key());
        UnitQueueMutation stale = new UnitQueueMutation(unit.playerId, unit.unitId, UnitQueueOperation.POLICY,
                Math.max(0, current - 1), QueuedUnitCommand.policy(world.activeSystemId(), CombatStance.AGGRESSIVE, null));
        require(UnitCommandQueueSystem.applyGlobal(world, stale) == UnitQueueApplyResult.STALE,
                "stale combat policy revision was not rejected");
        require(CombatPolicySystem.stance(world, unit) == CombatStance.PASSIVE,
                "stale policy mutation changed authoritative state");
    }

    private static void validateWireRoundTripAndOwnerIsolation() {
        World world = world("Combat policy wire");
        Unit unit = armedSoloUnit(world, 9209, world.width * 0.45, world.height * 0.48);
        UnitQueueMutation mutation = new UnitQueueMutation(unit.playerId, unit.unitId, UnitQueueOperation.POLICY,
                UnitCommandQueueSystem.revision(world, unit.key()),
                QueuedUnitCommand.policy(world.activeSystemId(), CombatStance.DEFENSIVE,
                        TargetPriorityPolicy.PROTECT_ASSIGNED_TARGET));
        String packet = UnitQueueWire.mutationPacket(mutation);
        UnitQueueMutation decoded = UnitQueueWire.parseMutation(packet.split("\\|", -1), unit.playerId);
        require(decoded.operation() == UnitQueueOperation.POLICY,
                "policy mutation wire round-trip lost the operation");
        require(UnitCommandQueueSystem.applyGlobal(world, decoded) == UnitQueueApplyResult.APPLIED,
                "wire-decoded policy mutation should apply");

        List<String> own = UnitCommandQueueSystem.statePackets(world, unit.playerId, true);
        List<String> other = UnitCommandQueueSystem.statePackets(world, "OTHER", true);
        require(!own.isEmpty(), "owner initial sync should contain combat policy state");
        require(other.isEmpty(), "combat policy state leaked to another player");

        World client = world("Combat policy wire client");
        PlayerRegistry.activate(client);
        require(UnitQueueWire.readState(client, own.get(0), unit.playerId),
                "owner combat policy state packet should decode");
        require(UnitCommandQueueSystem.combatStance(client, unit.key()) == CombatStance.DEFENSIVE,
                "combat stance did not survive authoritative state sync");
        require(UnitCommandQueueSystem.targetPriority(client, unit.key()) == TargetPriorityPolicy.PROTECT_ASSIGNED_TARGET,
                "target priority did not survive authoritative state sync");
    }

    private static void validateGalaxySaveRestore() {
        World world = world("Combat policy save");
        Unit unit = armedSoloUnit(world, 9210, world.width * 0.45, world.height * 0.48);
        require(applyPolicy(world, unit, CombatStance.DEFENSIVE, TargetPriorityPolicy.SCREENING)
                        == UnitQueueApplyResult.APPLIED,
                "save policy should apply");
        long revision = UnitCommandQueueSystem.revision(world, unit.key());
        Map<String,Object> galaxy = world.captureServerSaveGalaxy();

        World restored = new World("Combat policy restored", Set.of(), world.systemId(), false);
        PlayerRegistry.activate(restored);
        restored.restoreServerSaveGalaxy(galaxy);
        String systemId = restored.ownerUnitLocations("SOLO").get(unit.key());
        require(systemId != null && !systemId.isBlank(), "restored policy unit should exist");
        restored.activateSystem(systemId);
        Unit restoredUnit = restored.units.get(unit.key());
        require(restoredUnit != null, "restored policy unit should load");
        require(UnitCommandQueueSystem.combatStance(restored, restoredUnit.key()) == CombatStance.DEFENSIVE,
                "combat stance did not survive galaxy save/restore");
        require(UnitCommandQueueSystem.targetPriority(restored, restoredUnit.key()) == TargetPriorityPolicy.SCREENING,
                "target priority did not survive galaxy save/restore");
        require(UnitCommandQueueSystem.revision(restored, restoredUnit.key()) == revision,
                "combat policy revision did not survive galaxy save/restore");
    }

    private static void validateWormholeContinuity() {
        World world = world("Combat policy wormhole");
        Unit unit = soloUnit(world);
        require(!world.wormholes.isEmpty(), "combat policy wormhole fixture requires a gate");
        require(applyPolicy(world, unit, CombatStance.PASSIVE, TargetPriorityPolicy.STRUCTURES_LAST)
                        == UnitQueueApplyResult.APPLIED,
                "wormhole policy should apply");
        WormholeGate gate = world.wormholes.get(0);
        String source = world.activeSystemId();
        String destination = gate.toSystemId;
        require(apply(world, unit, UnitQueueOperation.REPLACE,
                QueuedUnitCommand.wormhole(source, gate.id, destination)) == UnitQueueApplyResult.APPLIED,
                "wormhole command should apply after a policy mutation");
        runUntil(world, () -> destination.equals(world.ownerUnitLocations(unit.playerId).get(unit.key())), 1800,
                "policy unit did not transit the wormhole");
        require(UnitCommandQueueSystem.combatStance(world, unit.key()) == CombatStance.PASSIVE,
                "combat stance did not survive wormhole transit");
        require(UnitCommandQueueSystem.targetPriority(world, unit.key()) == TargetPriorityPolicy.STRUCTURES_LAST,
                "target priority did not survive wormhole transit");
    }

    private static UnitQueueApplyResult applyPolicy(World world, Unit unit, CombatStance stance,
                                                    TargetPriorityPolicy priority) {
        return apply(world, unit, UnitQueueOperation.POLICY,
                QueuedUnitCommand.policy(world.activeSystemId(), stance, priority));
    }

    private static UnitQueueApplyResult apply(World world, Unit unit, UnitQueueOperation operation,
                                              QueuedUnitCommand command) {
        UnitQueueMutation mutation = new UnitQueueMutation(unit.playerId, unit.unitId, operation,
                UnitCommandQueueSystem.revision(world, unit.key()), command);
        return UnitCommandQueueSystem.applyGlobal(world, mutation);
    }

    private static World world(String name) {
        PlayerRegistry.reset("SOLO", name, 0x50BEFF);
        World world = new World(name, Set.of(), StarSystems.DEFAULT_SYSTEM_ID);
        PlayerRegistry.activate(world);
        return world;
    }

    private static Unit soloUnit(World world) {
        for (Unit unit : world.units.values()) if ("SOLO".equals(unit.playerId)) return unit;
        throw new IllegalStateException("Solo unit missing.");
    }

    private static Unit armedSoloUnit(World world, int unitId, double x, double y) {
        Unit unit = new Unit("SOLO", unitId, "destroyer", x, y);
        unit.loadoutId = "destroyer_rail_escort";
        world.units.put(unit.key(), unit);
        world.saveActiveSystem();
        return unit;
    }

    private static Unit hostileDestroyer(World world, int unitId, double x, double y) {
        return hostileUnit(world, unitId, "destroyer", x, y);
    }

    private static Unit hostileUnit(World world, int unitId, String hullId, double x, double y) {
        Unit unit = new Unit(Config.CORSAIRS_ID, unitId, hullId, x, y);
        ShipLoadoutDefinition defaultLoadout = WeaponRules.findLoadout(WeaponRules.defaultLoadoutId(hullId));
        if (defaultLoadout != null) unit.loadoutId = defaultLoadout.id();
        world.units.put(unit.key(), unit);
        world.saveActiveSystem();
        return unit;
    }

    private static Unit pointDefenseSoloUnit(World world, int unitId, double x, double y) {
        for (ShipType hull : Rules.SHIPS.values()) {
            for (ShipLoadoutDefinition loadout : WeaponRules.loadoutsForHull(hull.id)) {
                Unit candidate = new Unit("SOLO", unitId, hull.id, x, y);
                candidate.loadoutId = loadout.id();
                if (!WeaponRules.screenWeapons(world, candidate).isEmpty()) {
                    world.units.put(candidate.key(), candidate);
                    world.saveActiveSystem();
                    return candidate;
                }
            }
        }
        throw new IllegalStateException("No point-defense loadout available for issue 292 validation.");
    }

    private static void runUntil(World world, Check condition, int steps, String failure) {
        for (int i = 0; i < steps; i++) {
            if (condition.ok()) return;
            world.update(0.05);
        }
        if (!condition.ok()) throw new IllegalStateException(failure);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    @FunctionalInterface
    private interface Check { boolean ok(); }
}
