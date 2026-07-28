package com.tndmadman.rts;

final class VisibilityRules {
    private static final double MIN_UNIT_SENSOR_RANGE = 260.0;
    private static final double DEDICATED_SCOUT_SENSOR_RANGE = 720.0;
    private static final double MIN_BASE_SENSOR_RANGE = 650.0;

    private VisibilityRules() { }

    static double unitSensorRange(World world, Unit unit) {
        if (unit == null) return 0;
        double baseline = MIN_UNIT_SENSOR_RANGE + Math.max(0, unit.type().size.scale) * 70.0;
        double configuredScoutRange = Math.max(0, unit.type().scoutRange);
        double range = Math.max(baseline, configuredScoutRange);
        if ("scout".equals(unit.shipTypeId)) range = Math.max(range, DEDICATED_SCOUT_SENSOR_RANGE);
        return range * SystemModifierRules.sensorRange(world);
    }

    static double baseSensorRange(World world, Base base) {
        if (base == null) return 0;
        double range = Math.max(MIN_BASE_SENSOR_RANGE, Math.max(0, base.type().unloadRange) * 4.5);
        return range * SystemModifierRules.sensorRange(world);
    }

    static boolean pointVisible(World world, String playerId, double x, double y) {
        if (world == null || playerId == null || playerId.isBlank() || !Double.isFinite(x) || !Double.isFinite(y)) return false;
        for (Unit unit : world.units.values()) {
            if (!playerId.equals(unit.playerId) || unit.hp <= 0) continue;
            if (Calc.distance(unit.x, unit.y, x, y) <= unitSensorRange(world, unit)) return true;
        }
        for (Base base : world.bases.values()) {
            if (!playerId.equals(base.playerId) || base.hp <= 0) continue;
            if (Calc.distance(base.x, base.y, x, y) <= baseSensorRange(world, base)) return true;
        }
        return false;
    }

    static boolean unitVisible(World world, String playerId, Unit unit) {
        return unit != null && (playerId.equals(unit.playerId) || pointVisible(world, playerId, unit.x, unit.y));
    }

    static boolean baseVisible(World world, String playerId, Base base) {
        return base != null && (playerId.equals(base.playerId) || pointVisible(world, playerId, base.x, base.y));
    }

    static boolean targetVisible(World world, String playerId, String targetKey) {
        Unit unit = CombatTarget.unit(world, targetKey);
        if (unit != null) return unitVisible(world, playerId, unit);
        Base base = CombatTarget.base(world, targetKey);
        return base != null && baseVisible(world, playerId, base);
    }
}
