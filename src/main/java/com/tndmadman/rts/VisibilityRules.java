package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.List;

final class VisibilityRules {
    private VisibilityRules() { }

    static double unitSensorRange(World world, Unit unit) {
        return IntelWarfareSystem.ordinaryUnitRange(world, unit);
    }

    static double baseSensorRange(World world, Base base) {
        return IntelWarfareSystem.baseSensorRange(world, base);
    }

    static IntelWarfareSystem.DetectionStage unitStage(World world, String playerId, Unit unit) {
        return IntelWarfareSystem.unitStage(world, playerId, unit);
    }

    static IntelWarfareSystem.DetectionStage baseStage(World world, String playerId, Base base) {
        return IntelWarfareSystem.baseStage(world, playerId, base);
    }

    static IntelWarfareSystem.DetectionStage resourceStage(World world, String playerId, ResourceNode resource) {
        return IntelWarfareSystem.resourceStage(world, playerId, resource);
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
        private final World world;
        private final String playerId;
        private final List<Sensor> sensors;

        private Frame(World world, String playerId) {
            this.world = world;
            this.playerId = playerId == null ? "" : playerId;
            List<Sensor> found = new ArrayList<>();
            for (IntelWarfareSystem.IntelSensor sensor : IntelWarfareSystem.sensors(world, this.playerId)) {
                found.add(sensor(sensor.x(), sensor.y(), sensor.range()));
            }
            sensors = List.copyOf(found);
        }

        List<Sensor> sensors() { return sensors; }

        boolean pointVisible(double x, double y) {
            if (!Double.isFinite(x) || !Double.isFinite(y)) return false;
            for (Sensor sensor : sensors) {
                double dx = x - sensor.x();
                double dy = y - sensor.y();
                if (dx * dx + dy * dy <= sensor.rangeSquared()) return true;
            }
            return false;
        }

        IntelWarfareSystem.DetectionStage unitStage(Unit unit) {
            return IntelWarfareSystem.unitStage(world, playerId, unit);
        }

        IntelWarfareSystem.DetectionStage baseStage(Base base) {
            return IntelWarfareSystem.baseStage(world, playerId, base);
        }

        IntelWarfareSystem.DetectionStage resourceStage(ResourceNode resource) {
            return IntelWarfareSystem.resourceStage(world, playerId, resource);
        }

        boolean unitVisible(Unit unit) {
            return unitStage(unit).atLeast(IntelWarfareSystem.DetectionStage.CONTACT);
        }

        boolean baseVisible(Base base) {
            return baseStage(base).atLeast(IntelWarfareSystem.DetectionStage.CONTACT);
        }

        boolean unitIdentified(Unit unit) {
            return unitStage(unit).atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED);
        }

        boolean baseIdentified(Base base) {
            return baseStage(base).atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED);
        }

        boolean targetVisible(World world, String targetKey) {
            Unit unit = CombatTarget.unit(world, targetKey);
            if (unit != null) return unitIdentified(unit);
            Base base = CombatTarget.base(world, targetKey);
            return base != null && baseIdentified(base);
        }

        private Sensor sensor(double x, double y, double range) {
            double safeRange = Double.isFinite(range) ? Math.max(0, range) : 0;
            return new Sensor(x, y, safeRange, safeRange * safeRange);
        }
    }

    record Sensor(double x, double y, double range, double rangeSquared) { }
}
