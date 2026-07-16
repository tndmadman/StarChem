package com.tndmadman.rts;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class NpcSquadCombatValidator {
    private static final double EPSILON = 0.001;

    private NpcSquadCombatValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem NPC squad combat validation passed.");
    }

    static void validateOrThrow() {
        validateDeterministicMembershipAndRoles();
        validateFocusFireAndOverkillControl();
        validateScreeningGeometry();
        validateRangeManagement();
        validateRegroupingAfterDispersion();
        validateWithdrawalAndRecoveryOwnership();
        validateGalaxyDirectorIntegration();
    }

    private static void validateDeterministicMembershipAndRoles() {
        Fixture first = fixture("NPC Squad Determinism A");
        addCorsair(first, 1, "frigate", 4200, 4000);
        addCorsair(first, 2, "destroyer", 4280, 4000);
        addCorsair(first, 3, "cruiser", 4360, 4000);
        addCorsair(first, 4, "frigate", 4440, 4000);
        Unit reserve = addCorsair(first, 5, "frigate", 4520, 4000);
        reserve.hp = reserve.type().maxHp * 0.82;
        NpcSquadCombatSystem.update(first.world, first.faction, NpcStrategicState.RAID, 1.0);
        NpcSquadView firstSquad = onlySquad(first);

        Fixture second = fixture("NPC Squad Determinism B");
        addCorsair(second, 1, "frigate", 4200, 4000);
        addCorsair(second, 2, "destroyer", 4280, 4000);
        addCorsair(second, 3, "cruiser", 4360, 4000);
        addCorsair(second, 4, "frigate", 4440, 4000);
        Unit secondReserve = addCorsair(second, 5, "frigate", 4520, 4000);
        secondReserve.hp = secondReserve.type().maxHp * 0.82;
        NpcSquadCombatSystem.update(second.world, second.faction, NpcStrategicState.RAID, 1.0);
        NpcSquadView secondSquad = onlySquad(second);

        require(firstSquad.members().equals(secondSquad.members()),
                "fixed world state produced different squad membership");
        require(firstSquad.roles().equals(secondSquad.roles()),
                "fixed world state produced different combat roles");
        require(firstSquad.roles().containsValue(NpcSquadRole.SCREEN),
                "point-defense destroyer was not assigned SCREEN role");
        require(firstSquad.roles().containsValue(NpcSquadRole.ARTILLERY),
                "long-range cruiser was not assigned ARTILLERY role");
        require(firstSquad.roles().containsValue(NpcSquadRole.RESERVE),
                "damaged or fifth squad member was not assigned RESERVE role");
        require(firstSquad.protectedKey().equals(Unit.key(first.faction.id(), 3)),
                "longest-range squad member was not selected as the protected ship");
    }

    private static void validateFocusFireAndOverkillControl() {
        Fixture fixture = fixture("NPC Squad Focus Fire");
        for (int i = 0; i < 4; i++) addCorsair(fixture, 10 + i, "frigate", 4100 + i * 45, 4000);
        Unit vulnerable = addEnemy(fixture, "FOCUS_ENEMY", 1, "destroyer", 4700, 4000);
        vulnerable.hp = 8.0;
        vulnerable.shield = 0;
        Base durable = new Base("FOCUS_ENEMY:B1", "FOCUS_ENEMY", "outpost", 4800, 4100);
        fixture.world.bases.put(durable.id, durable);

        NpcSquadCombatSystem.update(fixture.world, fixture.faction, NpcStrategicState.RAID, 1.0);
        NpcSquadView squad = onlySquad(fixture);
        Map<String, Integer> counts = targetCounts(squad.targets());
        String vulnerableKey = CombatTarget.unit(vulnerable);
        require(counts.getOrDefault(vulnerableKey, 0) <= 1,
                "multiple volleys were assigned to an already overkilled target");
        require(counts.size() >= 2,
                "squad did not distribute projected damage across viable targets");
        require(squad.mode() == NpcSquadMode.ENGAGING,
                "armed squad with valid targets did not enter ENGAGING mode");
    }

    private static void validateScreeningGeometry() {
        Fixture fixture = fixture("NPC Squad Screening");
        Unit screen = addCorsair(fixture, 20, "destroyer", 3700, 4000);
        Unit artillery = addCorsair(fixture, 21, "cruiser", 4000, 4000);
        Unit threat = addEnemy(fixture, "SCREEN_ENEMY", 1, "frigate", 4900, 4000);

        NpcSquadCombatSystem.update(fixture.world, fixture.faction, NpcStrategicState.RAID, 1.0);
        NpcSquadView squad = onlySquad(fixture);
        require(squad.roles().get(screen.key()) == NpcSquadRole.SCREEN,
                "point-defense ship did not retain SCREEN role");
        require(squad.protectedKey().equals(artillery.key()),
                "screen did not protect the long-range squad member");
        require(screen.task == UnitTask.MOVE,
                "screen did not move into its threat-facing formation slot");
        require(screen.targetX > artillery.x && screen.targetX < threat.x,
                "screen formation point was not between threat and protected ship");
        require(Math.abs(screen.targetY - artillery.y) < 140.0,
                "screen formation used an implausible lateral offset");
    }

    private static void validateRangeManagement() {
        Fixture close = fixture("NPC Squad Close Range");
        Unit artillery = addCorsair(close, 30, "cruiser", 4000, 4000);
        Base closeTarget = new Base("RANGE_ENEMY:B1", "RANGE_ENEMY", "outpost", 4100, 4000);
        close.world.bases.put(closeTarget.id, closeTarget);
        double before = Calc.distance(artillery.x, artillery.y, closeTarget.x, closeTarget.y);

        NpcSquadCombatSystem.update(close.world, close.faction, NpcStrategicState.RAID, 1.0);
        require(artillery.task == UnitTask.MOVE,
                "long-range ship did not disengage from an unfavorable close range");
        require(Calc.distance(artillery.targetX, artillery.targetY, closeTarget.x, closeTarget.y) > before,
                "range-management move did not increase separation from the target");

        Fixture far = fixture("NPC Squad Chase Range");
        Unit line = addCorsair(far, 31, "frigate", 3000, 4000);
        Unit farTarget = addEnemy(far, "RANGE_ENEMY", 2, "frigate", 5100, 4000);
        NpcSquadCombatSystem.update(far.world, far.faction, NpcStrategicState.RAID, 1.0);
        require(line.task == UnitTask.ATTACK
                        && CombatTarget.unit(farTarget).equals(line.attackTarget),
                "out-of-range line ship did not chase its assigned target");
    }

    private static void validateRegroupingAfterDispersion() {
        Fixture fixture = fixture("NPC Squad Regroup");
        Unit left = addCorsair(fixture, 40, "frigate", 1500, 4000);
        addCorsair(fixture, 41, "destroyer", 4300, 4000);
        Unit right = addCorsair(fixture, 42, "cruiser", 7100, 4000);

        NpcSquadCombatSystem.update(fixture.world, fixture.faction, NpcStrategicState.FORTIFY, 1.0);
        NpcSquadView squad = onlySquad(fixture);
        require(squad.mode() == NpcSquadMode.REGROUPING,
                "widely dispersed squad did not enter REGROUPING mode");
        require(left.task == UnitTask.MOVE && left.targetX > left.x,
                "left flank did not move toward the squad centroid");
        require(right.task == UnitTask.MOVE && right.targetX < right.x,
                "right flank did not move toward the squad centroid");
    }

    private static void validateWithdrawalAndRecoveryOwnership() {
        Fixture fixture = fixture("NPC Squad Withdrawal");
        Unit damaged = addCorsair(fixture, 50, "frigate", 5200, 4000);
        damaged.hp = damaged.type().maxHp * 0.50;
        damaged.issueMove(3900, 3900);
        double damagedTargetX = damaged.targetX;
        double damagedTargetY = damaged.targetY;

        Unit escort = addCorsair(fixture, 51, "destroyer", 5250, 4050);
        escort.orderType = UnitOrderType.ESCORT;
        escort.orderTarget = CombatTarget.unit(damaged);
        escort.orderRadius = UnitOrderSystem.defaultRadius(UnitOrderType.ESCORT);
        escort.task = UnitTask.IDLE;

        Unit line = addCorsair(fixture, 52, "cruiser", 5300, 4100);
        NpcSquadCombatSystem.update(fixture.world, fixture.faction, NpcStrategicState.RETREAT, 1.0);
        NpcSquadView squad = onlySquad(fixture);
        require(squad.mode() == NpcSquadMode.WITHDRAWING,
                "RETREAT strategy did not put the squad into WITHDRAWING mode");
        require(Math.abs(damaged.targetX - damagedTargetX) < EPSILON
                        && Math.abs(damaged.targetY - damagedTargetY) < EPSILON,
                "squad logic replaced the damaged ship's recovery movement");
        require(escort.orderType == UnitOrderType.ESCORT
                        && escort.orderTarget.equals(CombatTarget.unit(damaged)),
                "squad logic cleared a Phase 10 repair escort");
        require(line.task == UnitTask.MOVE
                        && Calc.distance(line.targetX, line.targetY,
                        fixture.base.x, fixture.base.y) < 260.0,
                "healthy withdrawing ship did not return toward a friendly station");
        require(squad.roles().get(damaged.key()) == NpcSquadRole.RETREATING_CASUALTY,
                "damaged ship was not detached as a retreating casualty");
    }

    private static void validateGalaxyDirectorIntegration() {
        Fixture fixture = fixture("NPC Squad Director Integration");
        addCorsair(fixture, 60, "frigate", fixture.base.x + 180, fixture.base.y);
        addCorsair(fixture, 61, "destroyer", fixture.base.x + 240, fixture.base.y);
        addEnemy(fixture, "DIRECTOR_ENEMY", 1, "frigate",
                fixture.base.x + 500, fixture.base.y);

        new NpcGalaxyDirector().update(fixture.world, 1.0);
        require(!NpcSquadCombatSystem.snapshot(fixture.world, fixture.faction).squads().isEmpty(),
                "galaxy director did not invoke local squad combat after strategy and expedition updates");
    }

    private static Fixture fixture(String name) {
        PlayerRegistry.reset("WAIT", name, 0x50BEFF);
        World world = new World(name,
                Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID),
                StarSystems.CORSAIR_SYSTEM_ID,
                false);
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        world.units.clear();
        world.bases.clear();
        NpcSquadCombatSystem.clear(world);
        NpcExpeditionSystem.clear(world);
        NpcStationConstructionSystem.clear(world);

        NpcFaction faction = corsairs();
        Base base = new Base(faction.id() + ":B1", faction.id(), "outpost", 4000, 4000);
        world.bases.put(base.id, base);
        return new Fixture(world, faction, base);
    }

    private static NpcFaction corsairs() {
        for (NpcFaction faction : NpcRules.factions()) {
            if (Config.CORSAIRS_ID.equals(faction.id())) return faction;
        }
        throw new IllegalStateException("Corsair faction is not configured");
    }

    private static Unit addCorsair(Fixture fixture, int id, String type, double x, double y) {
        Unit unit = new Unit(fixture.faction.id(), id, type, x, y);
        fixture.world.units.put(unit.key(), unit);
        return unit;
    }

    private static Unit addEnemy(Fixture fixture, String owner, int id,
                                 String type, double x, double y) {
        Unit unit = new Unit(owner, id, type, x, y);
        fixture.world.units.put(unit.key(), unit);
        return unit;
    }

    private static NpcSquadView onlySquad(Fixture fixture) {
        NpcSquadCombatSnapshot snapshot = NpcSquadCombatSystem.snapshot(
                fixture.world, fixture.faction);
        require(snapshot.squads().size() == 1,
                "fixture expected exactly one squad but found " + snapshot.squads().size());
        return snapshot.squads().get(0);
    }

    private static Map<String, Integer> targetCounts(Map<String, String> assignments) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String target : assignments.values()) counts.merge(target, 1, Integer::sum);
        return counts;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Fixture(World world, NpcFaction faction, Base base) { }
}
