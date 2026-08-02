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
        return frame(world, playerId).unitStage(unit);
    }

    static IntelWarfareSystem.DetectionStage baseStage(World world, String playerId, Base base) {
        return frame(world, playerId).baseStage(base);
    }

    static IntelWarfareSystem.DetectionStage resourceStage(World world, String playerId, ResourceNode resource) {
        return frame(world, playerId).resourceStage(resource);
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
        private final IntelWarfareSystem.DetectionFrame detection;
        private final List<Sensor> sensors;
        private final List<Unit> resourceSurveyUnits;

        private Frame(World world, String playerId) {
            this.world = world;
            this.playerId = playerId == null ? "" : playerId;
            detection = IntelWarfareSystem.frame(world, this.playerId);

            List<Sensor> found = new ArrayList<>(detection.sensors().size());
            for (IntelWarfareSystem.IntelSensor sensor : detection.sensors()) {
                found.add(sensor(sensor.x(), sensor.y(), sensor.range()));
            }
            sensors = List.copyOf(found);

            List<Unit> surveyUnits = new ArrayList<>();
            if (world != null) {
                for (Unit unit : world.units.values()) {
                    if (unit == null || unit.hp <= 0
                            || !IntelWarfareSystem.allied(world, this.playerId, unit.playerId)
                            || unit.type().harvestKinds.isEmpty()) continue;
                    surveyUnits.add(unit);
                }
            }
            resourceSurveyUnits = List.copyOf(surveyUnits);
        }

        List<Sensor> sensors() { return sensors; }

        boolean pointVisible(double x, double y) {
            return IntelWarfareSystem.pointVisible(detection, x, y);
        }

        IntelWarfareSystem.DetectionStage unitStage(Unit unit) {
            return IntelWarfareSystem.unitStage(detection, unit);
        }

        IntelWarfareSystem.DetectionStage baseStage(Base base) {
            return IntelWarfareSystem.baseStage(detection, base);
        }

        IntelWarfareSystem.DetectionStage resourceStage(ResourceNode resource) {
            IntelWarfareSystem.DetectionStage stage = IntelWarfareSystem.resourceStage(detection, resource);
            if (world == null || resource == null || !resource.active) return stage;
            for (Unit unit : resourceSurveyUnits) {
                if (!unit.type().harvestKinds.contains(resource.kind)) continue;
                double localSurveyRange = Math.max(unit.type().scoutRange,
                        Math.max(unit.type().harvestRange * 3.0, 180.0))
                        * SystemModifierRules.sensorRange(world);
                double dx = unit.x - resource.x;
                double dy = unit.y - resource.y;
                double distanceSquared = dx * dx + dy * dy;
                if (distanceSquared > localSurveyRange * localSurveyRange) continue;
                double detailedRange = Math.max(40, unit.type().harvestRange * 1.25);
                IntelWarfareSystem.DetectionStage local =
                        distanceSquared <= detailedRange * detailedRange
                                ? IntelWarfareSystem.DetectionStage.DETAILED
                                : IntelWarfareSystem.DetectionStage.IDENTIFIED;
                if (local.ordinal() > stage.ordinal()) stage = local;
            }
            return stage;
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
