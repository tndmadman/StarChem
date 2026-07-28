package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.List;

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

    static Frame frame(World world, String playerId) {
        return new Frame(world, playerId);
    }

    static boolean pointVisible(World world, String playerId, double x, double y) {
        return frame(world, playerId).pointVisible(x, y);
    }

    static boolean unitVisible(World world, String playerId, Unit unit) {
        return frame(world, playerId).unitVisible(unit);
    }

    static boolean baseVisible(World world, String playerId, Base base) {
        return frame(world, playerId).baseVisible(base);
    }

    static boolean targetVisible(World world, String playerId, String targetKey) {
        return frame(world, playerId).targetVisible(world, targetKey);
    }

    static final class Frame {
        private final String playerId;
        private final List<Sensor> sensors;

        private Frame(World world, String playerId) {
            this.playerId = playerId == null ? "" : playerId;
            List<Sensor> found = new ArrayList<>();
            if (world != null && !this.playerId.isBlank()) {
                for (Unit unit : world.units.values()) {
                    if (!this.playerId.equals(unit.playerId) || unit.hp <= 0) continue;
                    found.add(sensor(unit.x, unit.y, unitSensorRange(world, unit)));
                }
                for (Base base : world.bases.values()) {
                    if (!this.playerId.equals(base.playerId) || base.hp <= 0) continue;
                    found.add(sensor(base.x, base.y, baseSensorRange(world, base)));
                }
            }
            sensors = List.copyOf(found);
        }

        boolean pointVisible(double x, double y) {
            if (!Double.isFinite(x) || !Double.isFinite(y)) return false;
            for (Sensor sensor : sensors) {
                double dx = x - sensor.x();
                double dy = y - sensor.y();
                if (dx * dx + dy * dy <= sensor.rangeSquared()) return true;
            }
            return false;
        }

        boolean unitVisible(Unit unit) {
            return unit != null && (playerId.equals(unit.playerId) || pointVisible(unit.x, unit.y));
        }

        boolean baseVisible(Base base) {
            return base != null && (playerId.equals(base.playerId) || pointVisible(base.x, base.y));
        }

        boolean targetVisible(World world, String targetKey) {
            Unit unit = CombatTarget.unit(world, targetKey);
            if (unit != null) return unitVisible(unit);
            Base base = CombatTarget.base(world, targetKey);
            return base != null && baseVisible(base);
        }

        private Sensor sensor(double x, double y, double range) {
            double safeRange = Double.isFinite(range) ? Math.max(0, range) : 0;
            return new Sensor(x, y, safeRange * safeRange);
        }
    }

    private record Sensor(double x, double y, double rangeSquared) { }
}
