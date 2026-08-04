package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AtomicRefitTransactionValidator {
    private AtomicRefitTransactionValidator() { }

    public static void main(String[] args) {
        String player = "ATOMIC_REFIT";
        PlayerRegistry.reset(player, "Atomic Refit", 0x55CCFF);
        World world = new World("Atomic Refit", Set.of(),
                StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);

        ShipFitSpec spec = new ShipFitSpec("prospector", List.of(),
                List.of("afterburner"));
        for (String topic : PlayerFitRules.requiredResearch(spec)) {
            world.completeResearch(player, topic);
        }

        Base outpost = new Base(player + ":B1", player,
                "outpost", 700, 700);
        Base shipyard = new Base(player + ":B2", player,
                "shipyard", 3600, 700);
        world.bases.put(outpost.id, outpost);
        world.bases.put(shipyard.id, shipyard);
        for (Base base : List.of(outpost, shipyard)) {
            for (Material material : Material.values()) {
                base.inventory.put(material, 1000.0);
            }
        }

        List<Unit> ships = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            double x = i < 2 ? 760 + i * 60 : 3540 + i * 30;
            Unit unit = new Unit(player, i + 1, "prospector", x, 700);
            unit.loadoutId = WeaponRules.defaultLoadoutId("prospector");
            unit.task = UnitTask.ATTACK;
            unit.attackTarget = "B:test-target";
            unit.targetX = x + 500;
            unit.targetY = 900;
            unit.orderType = UnitOrderType.PATROL;
            unit.orderX1 = x;
            unit.orderY1 = 700;
            unit.orderX2 = x + 200;
            unit.orderY2 = 900;
            unit.orderRadius = 40;
            unit.automationResourceId = 7;
            unit.afterburnerActive = true;
            world.units.put(unit.key(), unit);
            ships.add(unit);
        }
        String runtimeId = spec.runtimeId();

        Map<Base,EnumMap<Material,Double>> beforeInventory = Map.of(
                outpost, new EnumMap<>(outpost.inventory),
                shipyard, new EnumMap<>(shipyard.inventory));
        List<State> beforeState = ships.stream().map(State::capture).toList();
        long revision = WorldFitCatalog.revision(world);
        RefitQueuePlanner.failAfterInsertionsForTest(1);
        RefitQueuePlanner.Result failed = RefitQueuePlanner.enqueueCustom(
                world, player, ships, "Atomic Afterburner", spec, false, outpost);
        require(!failed.success(), "injected transaction failure was accepted");
        require(outpost.productionQueue.isEmpty()
                        && shipyard.productionQueue.isEmpty(),
                "failed transaction left queue jobs behind");
        require(WorldFitCatalog.revision(world) == revision
                        && !catalogContains(world, runtimeId),
                "failed transaction polluted the runtime catalog");
        for (Base base : List.of(outpost, shipyard)) {
            require(base.inventory.equals(beforeInventory.get(base)),
                    "failed transaction changed inventory at " + base.id);
        }
        for (int i = 0; i < ships.size(); i++) {
            beforeState.get(i).requireSame(ships.get(i));
        }

        RefitQueuePlanner.Result success = RefitQueuePlanner.enqueueCustom(
                world, player, ships, "Atomic Afterburner", spec, false, outpost);
        require(success.success() && success.queued() == ships.size(),
                "atomic class refit did not queue every ship: " + success.message());
        require(success.stationsUsed() == 2,
                "atomic planner did not distribute nearby ships across both stations");
        require(catalogContains(world, runtimeId),
                "successful transaction did not register runtime fit");
        int queued = outpost.productionQueue.size() + shipyard.productionQueue.size();
        require(queued == ships.size(),
                "successful transaction queued an incorrect job count");

        for (Base base : List.of(outpost, shipyard)) {
            EnumMap<Material,Double> reserved = new EnumMap<>(Material.class);
            for (ProductionJob job : base.productionQueue) {
                require(job.refitQuoteVersion == RefitQuote.CURRENT_VERSION,
                        "job lost quote version");
                for (Cost cost : job.reservedCost) {
                    reserved.merge(cost.material(), cost.amount(), Double::sum);
                }
            }
            for (Material material : Material.values()) {
                double expected = beforeInventory.get(base)
                        .getOrDefault(material, 0.0)
                        - reserved.getOrDefault(material, 0.0);
                require(close(base.inventory.getOrDefault(material, 0.0), expected),
                        "station aggregate reservation is wrong for "
                                + material + " at " + base.id);
            }
        }

        for (Base base : List.of(outpost, shipyard)) {
            for (ProductionJob job : List.copyOf(base.productionQueue)) {
                require(ProductionSystem.cancel(world, player, base.id, job.id),
                        "could not cancel atomic refit");
            }
            require(base.inventory.equals(beforeInventory.get(base)),
                    "atomic cancellation did not restore exact inventory at "
                            + base.id);
        }
        System.out.println(
                "StarChem atomic distributed refit transaction validation passed.");
    }

    private static boolean catalogContains(World world, String id) {
        for (Object item : ServerSaveStore.list(
                WorldFitCatalog.networkView(world).get("definitions"))) {
            if (id.equals(ServerSaveStore.string(
                    ServerSaveStore.object(item), "id", ""))) return true;
        }
        return false;
    }

    private static boolean close(double left, double right) {
        return Math.abs(left - right) < 0.000001;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record State(UnitTask task, String attackTarget,
                         String logisticsTargetBaseId, String logisticsRequestId,
                         String orderTarget, UnitOrderType orderType,
                         double targetX, double targetY, double heading,
                         double orbitAngle, double orbitRetarget,
                         double weaponCooldown, double weaponFlashTimer,
                         double wormholeCooldown, double microJumpCooldown,
                         double microJumpFlashTimer,
                         double miningAnchorX, double miningAnchorY,
                         double orderX1, double orderY1,
                         double orderX2, double orderY2, double orderRadius,
                         int automationResourceId, int orderPhase,
                         boolean miningAnchorSet, boolean afterburnerActive) {
        static State capture(Unit unit) {
            return new State(unit.task, unit.attackTarget,
                    unit.logisticsTargetBaseId, unit.logisticsRequestId,
                    unit.orderTarget, unit.orderType,
                    unit.targetX, unit.targetY, unit.heading,
                    unit.orbitAngle, unit.orbitRetarget,
                    unit.weaponCooldown, unit.weaponFlashTimer,
                    unit.wormholeCooldown, unit.microJumpCooldown,
                    unit.microJumpFlashTimer,
                    unit.miningAnchorX, unit.miningAnchorY,
                    unit.orderX1, unit.orderY1,
                    unit.orderX2, unit.orderY2, unit.orderRadius,
                    unit.automationResourceId, unit.orderPhase,
                    unit.miningAnchorSet, unit.afterburnerActive);
        }

        void requireSame(Unit unit) {
            require(unit.task == task
                            && attackTarget.equals(unit.attackTarget)
                            && logisticsTargetBaseId.equals(unit.logisticsTargetBaseId)
                            && logisticsRequestId.equals(unit.logisticsRequestId)
                            && orderTarget.equals(unit.orderTarget)
                            && unit.orderType == orderType
                            && close(unit.targetX, targetX)
                            && close(unit.targetY, targetY)
                            && close(unit.heading, heading)
                            && close(unit.orbitAngle, orbitAngle)
                            && close(unit.orbitRetarget, orbitRetarget)
                            && close(unit.weaponCooldown, weaponCooldown)
                            && close(unit.weaponFlashTimer, weaponFlashTimer)
                            && close(unit.wormholeCooldown, wormholeCooldown)
                            && close(unit.microJumpCooldown, microJumpCooldown)
                            && close(unit.microJumpFlashTimer, microJumpFlashTimer)
                            && close(unit.miningAnchorX, miningAnchorX)
                            && close(unit.miningAnchorY, miningAnchorY)
                            && close(unit.orderX1, orderX1)
                            && close(unit.orderY1, orderY1)
                            && close(unit.orderX2, orderX2)
                            && close(unit.orderY2, orderY2)
                            && close(unit.orderRadius, orderRadius)
                            && unit.automationResourceId == automationResourceId
                            && unit.orderPhase == orderPhase
                            && unit.miningAnchorSet == miningAnchorSet
                            && unit.afterburnerActive == afterburnerActive,
                    "failed transaction changed ship state for " + unit.key());
        }
    }
}
