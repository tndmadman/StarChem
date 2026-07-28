package com.tndmadman.rts;

import java.util.Set;

public final class FogOfWarValidator {
    private FogOfWarValidator() { }

    public static void main(String[] args) {
        World world = new World("Fog validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("P1", "Observer", 0x50BEFF);
        PlayerRegistry.register("P2", "Opponent", 0xFF5F55, false);

        world.units.clear();
        world.bases.clear();
        world.resources.clear();
        world.shots.clear();
        world.items.clear();

        Unit scout = new Unit("P1", 1, "scout", 1_000, 1_000);
        Unit ordinary = new Unit("P1", 2, "frigate", 1_000, 1_000);
        Unit visibleEnemy = new Unit("P2", 1, "frigate", 1_500, 1_000);
        Unit hiddenEnemy = new Unit("P2", 2, "frigate", 3_000, 1_000);
        visibleEnemy.addCargo(Material.IRON, 40);
        visibleEnemy.attackTarget = CombatTarget.unit(hiddenEnemy);
        visibleEnemy.orderTarget = CombatTarget.unit(hiddenEnemy);
        world.units.put(scout.key(), scout);
        world.units.put(ordinary.key(), ordinary);
        world.units.put(visibleEnemy.key(), visibleEnemy);
        world.units.put(hiddenEnemy.key(), hiddenEnemy);

        Base visibleBase = new Base("P2:B1", "P2", Rules.DEFAULT_BASE, 1_450, 1_250);
        Base hiddenBase = new Base("P2:B2", "P2", Rules.DEFAULT_BASE, 4_000, 1_000);
        visibleBase.inventory.put(Material.COPPER, 25.0);
        world.bases.put(visibleBase.id, visibleBase);
        world.bases.put(hiddenBase.id, hiddenBase);

        ResourceNode visibleResource = new ResourceNode(1, "Visible iron", NodeKind.SILICATE_ROCK,
                Material.IRON, 1_350, 1_000, 100, 5, 3);
        ResourceNode hiddenResource = new ResourceNode(2, "Hidden iron", NodeKind.SILICATE_ROCK,
                Material.IRON, 4_500, 1_000, 100, 5, 3);
        world.resources.add(visibleResource);
        world.resources.add(hiddenResource);

        require(VisibilityRules.unitSensorRange(world, scout) > VisibilityRules.unitSensorRange(world, ordinary),
                "Dedicated scouts must reveal farther than ordinary ships.");
        require(VisibilityRules.targetVisible(world, "P1", CombatTarget.unit(visibleEnemy)),
                "Enemy inside scout range should be target-visible.");
        require(!VisibilityRules.targetVisible(world, "P1", CombatTarget.unit(hiddenEnemy)),
                "Enemy outside all friendly sensors must stay hidden.");

        ordinary.attackTarget = "";
        AUnitAttack.apply(world, new AttackCommand("P1", ordinary.unitId, CombatTarget.unit(hiddenEnemy)));
        require(ordinary.attackTarget.isBlank(), "Hidden target key bypassed authoritative attack validation.");
        AUnitAttack.apply(world, new AttackCommand("P1", ordinary.unitId, CombatTarget.unit(visibleEnemy)));
        require(CombatTarget.unit(visibleEnemy).equals(ordinary.attackTarget),
                "Sensor-visible target was rejected by authoritative attack validation.");
        ordinary.attackTarget = "";
        ordinary.task = UnitTask.IDLE;

        ResourceSyncMode.fullForNextSnapshot();
        Snapshot filtered = FogSnapshotFilter.forPlayer(world, "P1", WorldNetAccess.snapshot(world, 1));

        require(hasUnit(filtered, scout.key()), "Own scout was removed from its snapshot.");
        require(hasUnit(filtered, visibleEnemy.key()), "Visible enemy was omitted from the snapshot.");
        require(!hasUnit(filtered, hiddenEnemy.key()), "Hidden enemy leaked into the snapshot.");
        require(hasBase(filtered, visibleBase.id), "Visible enemy base was omitted from the snapshot.");
        require(!hasBase(filtered, hiddenBase.id), "Hidden enemy base leaked into the snapshot.");
        require(hasResource(filtered, visibleResource.id), "Visible resource was omitted from a full correction.");
        require(!hasResource(filtered, hiddenResource.id), "Hidden resource leaked through a full correction.");

        UnitState visibleState = unit(filtered, visibleEnemy.key());
        require(visibleState != null && visibleState.cargo().isBlank(), "Enemy cargo leaked through fog filtering.");
        require(visibleState.attackTarget().isBlank(), "Hidden attack target leaked through a visible enemy state.");
        require(visibleState.orderTarget().isBlank(), "Hidden order target leaked through a visible enemy state.");

        BaseState visibleBaseState = base(filtered, visibleBase.id);
        require(visibleBaseState != null && visibleBaseState.cargo().isBlank(), "Enemy base inventory leaked through fog filtering.");
        require(visibleBaseState.productionQueue().isBlank(), "Enemy production queue leaked through fog filtering.");

        FogOfWarView.forceRefreshForTest(world);
        require(FogOfWarView.exploredCellCount(world) > 0, "Friendly sensors did not reveal explored fog cells.");
        require(FogOfWarView.lastKnownContactCount(world) >= 2,
                "Visible enemy ship and station were not recorded as observed contacts.");

        visibleEnemy.x = 2_200;
        visibleEnemy.targetX = visibleEnemy.x;
        world.systemTime += 0.5;
        FogOfWarView.forceRefreshForTest(world);
        require(FogOfWarView.recentHiddenContactCount(world) >= 1,
                "Enemy leaving sensor coverage did not leave a recent last-known contact.");

        world.systemTime += 3.0;
        FogOfWarView.forceRefreshForTest(world);
        require(FogOfWarView.recentHiddenContactCount(world) == 0,
                "Confirmed-clear last-known contact was not retired after the grace period.");

        System.out.println("Fog-of-war validator passed.");
    }

    private static boolean hasUnit(Snapshot snapshot, String key) { return unit(snapshot, key) != null; }

    private static UnitState unit(Snapshot snapshot, String key) {
        for (UnitState state : snapshot.units()) if (Unit.key(state.playerId(), state.unitId()).equals(key)) return state;
        return null;
    }

    private static boolean hasBase(Snapshot snapshot, String id) { return base(snapshot, id) != null; }

    private static BaseState base(Snapshot snapshot, String id) {
        for (BaseState state : snapshot.bases()) if (state.id().equals(id)) return state;
        return null;
    }

    private static boolean hasResource(Snapshot snapshot, int id) {
        for (ResourceState state : snapshot.resources()) if (state.id() == id) return true;
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
