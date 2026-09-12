package com.tndmadman.rts;

final class SystemControlBonuses {
    private SystemControlBonuses() { }

    static double miningYield(World world, String ownerId) {
        return resolved(world, ownerId, Benefit.MINING) * celestial(world, ownerId, CelestialBonusKind.MINING);
    }

    static double shieldRegen(World world, String ownerId) {
        return resolved(world, ownerId, Benefit.SHIELD_REGEN) * celestial(world, ownerId, CelestialBonusKind.SHIELD);
    }

    static double productionThroughput(World world, String ownerId) {
        return resolved(world, ownerId, Benefit.PRODUCTION) * celestial(world, ownerId, CelestialBonusKind.PRODUCTION);
    }

    static double researchThroughput(World world, String ownerId) {
        return resolved(world, ownerId, Benefit.RESEARCH) * celestial(world, ownerId, CelestialBonusKind.RESEARCH);
    }

    static double refitThroughput(World world, String ownerId) {
        return resolved(world, ownerId, Benefit.REFIT) * celestial(world, ownerId, CelestialBonusKind.REPAIR);
    }

    static double sensorRange(World world, String ownerId) {
        return resolved(world, ownerId, Benefit.SENSOR) * celestial(world, ownerId, CelestialBonusKind.SENSOR);
    }

    static double logisticsThroughput(World world, String ownerId) {
        return resolved(world, ownerId, Benefit.LOGISTICS) * celestial(world, ownerId, CelestialBonusKind.LOGISTICS);
    }

    static double repairThroughput(World world, String ownerId) {
        return resolved(world, ownerId, Benefit.REPAIR) * celestial(world, ownerId, CelestialBonusKind.REPAIR);
    }

    static StrategicSupplyState supplyState(World world, String ownerId) {
        if (!controls(world, ownerId)) return StrategicSupplyState.ISOLATED;
        return StrategicSupplyService.state(world, ownerId, world.activeSystemId());
    }

    private static double celestial(World world, String ownerId, CelestialBonusKind kind) {
        if (world == null || ownerId == null || ownerId.isBlank()) return 1.0;
        String activeId = world.activeSystemId();
        for (WorldSystemState state : world.policySystemStates()) {
            if (state != null && state.id.equals(activeId)) {
                return CelestialGameplaySystem.bonusMultiplier(state, ownerId, kind);
            }
        }
        return 1.0;
    }

    private static double resolved(World world, String ownerId, Benefit benefit) {
        if (!controls(world, ownerId)) return 1.0;
        // activeSystemId() is the galaxy-instance id and may differ from the template id used by
        // StarSystems. systemId() is the active StarSystemDefinition id, so strategic bonuses stay
        // correct for copied/dynamic systems as well as the primary template instance.
        StarSystemDefinition definition = StarSystems.get(world.systemId());
        SystemStrategicDefinition strategic = definition == null
                ? SystemStrategicDefinition.STANDARD : definition.strategic();
        double configured = switch (benefit) {
            case MINING -> strategic.miningYield();
            case SHIELD_REGEN -> strategic.shieldRegen();
            case PRODUCTION -> strategic.productionThroughput();
            case RESEARCH -> strategic.researchThroughput();
            case REFIT -> strategic.refitThroughput();
            case SENSOR -> strategic.sensorRange();
            case LOGISTICS -> strategic.logisticsThroughput();
            case REPAIR -> strategic.repairThroughput();
        };
        StrategicSupplyState supply = supplyState(world, ownerId);
        if (benefit == Benefit.MINING) return bonusOnly(configured, supply);
        return supplied(configured, supply, benefit.strainedFloor, benefit.isolatedFloor);
    }

    private static double bonusOnly(double configured, StrategicSupplyState supply) {
        double weight = switch (supply) {
            case SUPPLIED -> 1.0;
            case STRAINED -> 0.65;
            case ISOLATED -> 0.25;
        };
        return 1.0 + (configured - 1.0) * weight;
    }

    private static double supplied(double configured, StrategicSupplyState supply,
                                   double strainedFloor, double isolatedFloor) {
        return switch (supply) {
            case SUPPLIED -> configured;
            case STRAINED -> Math.max(strainedFloor, 1.0 + (configured - 1.0) * 0.65);
            case ISOLATED -> Math.max(isolatedFloor, 1.0 + (configured - 1.0) * 0.25);
        };
    }

    private static boolean controls(World world, String ownerId) {
        if (world == null || ownerId == null || ownerId.isBlank()) return false;
        return ownerId.equals(world.activeSystemControllerId());
    }

    private enum Benefit {
        MINING(1, 1),
        SHIELD_REGEN(0.92, 0.75),
        PRODUCTION(0.90, 0.70),
        RESEARCH(0.90, 0.75),
        REFIT(0.85, 0.60),
        SENSOR(0.90, 0.75),
        LOGISTICS(0.80, 0.50),
        REPAIR(0.80, 0.50);

        final double strainedFloor;
        final double isolatedFloor;

        Benefit(double strainedFloor, double isolatedFloor) {
            this.strainedFloor = strainedFloor;
            this.isolatedFloor = isolatedFloor;
        }
    }
}
