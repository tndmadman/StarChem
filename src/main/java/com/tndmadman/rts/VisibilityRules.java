package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class VisibilityRules {
    private static final double WORMHOLE_DISCOVERY_RADIUS = 18.0;

    // These thresholds intentionally mirror IntelWarfareSystem. Keeping the calculations in the
    // immutable frame avoids rebuilding the complete sensor list once for every contact query.
    private static final double CONTACT_THRESHOLD = 0.62;
    private static final double CLASSIFIED_THRESHOLD = 0.84;
    private static final double IDENTIFIED_THRESHOLD = 1.10;
    private static final double DETAILED_THRESHOLD = 1.52;

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
        private final List<IntelWarfareSystem.IntelSensor> detectionSensors;
        private final List<Sensor> sensors;
        private final List<ResourceRadar> resourceRadars;
        private final List<ResourceHarvester> resourceHarvesters;

        private Frame(World world, String playerId) {
            this.world = world;
            this.playerId = playerId == null ? "" : playerId;

            List<IntelWarfareSystem.IntelSensor> rawSensors = IntelWarfareSystem.sensors(world, this.playerId);
            detectionSensors = rawSensors;

            List<Sensor> found = new ArrayList<>();
            for (IntelWarfareSystem.IntelSensor sensor : rawSensors) {
                if (focusedRadarSensor(sensor)) continue;
                found.add(sensor(sensor.sourceKey(), sensor.x(), sensor.y(), sensor.range()));
            }
            addFocusedWormholeSensors(found);
            sensors = List.copyOf(found);

            List<ResourceRadar> radars = new ArrayList<>();
            List<ResourceHarvester> harvesters = new ArrayList<>();
            if (world != null) {
                for (Base base : world.bases.values()) {
                    if (base == null || base.hp <= 0
                            || !IntelWarfareSystem.allied(world, this.playerId, base.playerId)
                            || !IntelWarfareSystem.isRadar(base.typeId)
                            || StationControls.radarSearchTarget(world, base)
                            == StationControls.RadarSearchTarget.WORMHOLES) continue;
                    double range = baseSensorRange(world, base);
                    if (range > 0) {
                        radars.add(new ResourceRadar(base.x, base.y, range,
                                IntelWarfareSystem.surveyPower(base.typeId)));
                    }
                }
                for (Unit unit : world.units.values()) {
                    if (unit == null || unit.hp <= 0
                            || !IntelWarfareSystem.allied(world, this.playerId, unit.playerId)
                            || unit.type().harvestKinds.isEmpty()) continue;
                    double identifiedRange = Math.max(unit.type().scoutRange,
                            Math.max(unit.type().harvestRange * 3.0, 180.0))
                            * SystemModifierRules.sensorRange(world);
                    double detailedRange = Math.max(40, unit.type().harvestRange * 1.25);
                    harvesters.add(new ResourceHarvester(unit.x, unit.y, identifiedRange, detailedRange,
                            Set.copyOf(unit.type().harvestKinds)));
                }
            }
            resourceRadars = List.copyOf(radars);
            resourceHarvesters = List.copyOf(harvesters);
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
            if (unit == null || unit.hp <= 0) return IntelWarfareSystem.DetectionStage.NONE;
            if (IntelWarfareSystem.allied(world, playerId, unit.playerId)) {
                return IntelWarfareSystem.DetectionStage.DETAILED;
            }
            IntelWarfareSystem.DetectionStage stage = stageFor(unit.x, unit.y, unitSignature(unit), false);
            if (stage == IntelWarfareSystem.DetectionStage.NONE) return stage;
            if (StationControls.focusedAreaScanWouldCover(world, playerId, unit.x, unit.y)
                    && !pointVisible(unit.x, unit.y)) return IntelWarfareSystem.DetectionStage.NONE;
            return stage;
        }

        IntelWarfareSystem.DetectionStage baseStage(Base base) {
            if (base == null || base.hp <= 0) return IntelWarfareSystem.DetectionStage.NONE;
            if (IntelWarfareSystem.allied(world, playerId, base.playerId)) {
                return IntelWarfareSystem.DetectionStage.DETAILED;
            }
            IntelWarfareSystem.DetectionStage stage = stageFor(base.x, base.y, baseSignature(base), true);
            if (IntelWarfareSystem.isDecoy(base.typeId)
                    && stage == IntelWarfareSystem.DetectionStage.IDENTIFIED) {
                stage = IntelWarfareSystem.DetectionStage.CLASSIFIED;
            }
            if (stage == IntelWarfareSystem.DetectionStage.NONE) return stage;
            if (StationControls.focusedAreaScanWouldCover(world, playerId, base.x, base.y)
                    && !pointVisible(base.x, base.y)) return IntelWarfareSystem.DetectionStage.NONE;
            return stage;
        }

        IntelWarfareSystem.DetectionStage resourceStage(ResourceNode resource) {
            if (world == null || resource == null || !resource.active) {
                return IntelWarfareSystem.DetectionStage.NONE;
            }
            IntelWarfareSystem.DetectionStage stage = pointVisible(resource.x, resource.y)
                    ? IntelWarfareSystem.DetectionStage.CONTACT
                    : IntelWarfareSystem.DetectionStage.NONE;

            for (ResourceRadar radar : resourceRadars) {
                double distance = Calc.distance(radar.x, radar.y, resource.x, resource.y);
                if (distance > radar.range) continue;
                IntelWarfareSystem.DetectionStage radarStage = switch (radar.surveyPower) {
                    case 0 -> IntelWarfareSystem.DetectionStage.CONTACT;
                    case 1 -> IntelWarfareSystem.DetectionStage.CLASSIFIED;
                    case 2 -> IntelWarfareSystem.DetectionStage.IDENTIFIED;
                    default -> IntelWarfareSystem.DetectionStage.DETAILED;
                };
                if (distance > radar.range * 0.82
                        && radarStage.ordinal() > IntelWarfareSystem.DetectionStage.CONTACT.ordinal()) {
                    radarStage = IntelWarfareSystem.DetectionStage.values()[radarStage.ordinal() - 1];
                }
                if (radarStage.ordinal() > stage.ordinal()) stage = radarStage;
            }

            for (ResourceHarvester harvester : resourceHarvesters) {
                if (!harvester.harvestKinds.contains(resource.kind)) continue;
                double distance = Calc.distance(harvester.x, harvester.y, resource.x, resource.y);
                if (distance > harvester.identifiedRange) continue;
                IntelWarfareSystem.DetectionStage local = distance <= harvester.detailedRange
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

        private IntelWarfareSystem.DetectionStage stageFor(double x, double y, double signature, boolean station) {
            if (world == null || playerId.isBlank()) return IntelWarfareSystem.DetectionStage.NONE;
            double best = 0;
            double safeSignature = Math.max(0.15, signature);
            for (IntelWarfareSystem.IntelSensor sensor : detectionSensors) {
                double dx = x - sensor.x();
                double dy = y - sensor.y();
                double distance = Math.max(1, Math.hypot(dx, dy));
                double quality = sensor.range() / distance * safeSignature
                        + sensor.identificationBonus() + (station ? 0.04 : 0);
                if (quality > best) best = quality;
            }
            if (best >= DETAILED_THRESHOLD) return IntelWarfareSystem.DetectionStage.DETAILED;
            if (best >= IDENTIFIED_THRESHOLD) return IntelWarfareSystem.DetectionStage.IDENTIFIED;
            if (best >= CLASSIFIED_THRESHOLD) return IntelWarfareSystem.DetectionStage.CLASSIFIED;
            if (best >= CONTACT_THRESHOLD) return IntelWarfareSystem.DetectionStage.CONTACT;
            return IntelWarfareSystem.DetectionStage.NONE;
        }

        private double unitSignature(Unit unit) {
            double signature = 0.48 + Math.max(0.35, unit.type().size.scale) * 0.24;
            signature += Math.min(0.40, Math.max(0, unit.type().speed) / 900.0);
            if (unit.weaponFlashTimer > 0) signature += 1.15;
            if (unit.task == UnitTask.ATTACK) signature += 0.55;
            else if (unit.task == UnitTask.AUTO_HARVEST) signature += 0.30;
            else if (unit.task == UnitTask.MOVE) signature += 0.12;
            if (!unit.basePackageType.isBlank()) signature += 0.28;
            if (unit.shipTypeId.startsWith("sensor_contact_")) signature = 0.8;
            return signature;
        }

        private double baseSignature(Base base) {
            IntelWarfareSystem.StructureIntelRule rule = IntelWarfareSystem.rule(base.typeId);
            double signature = 1.0 + Math.min(1.2, Math.max(0, base.type().maxHp) / 2500.0);
            signature *= Math.max(0.25, rule.signatureMultiplier());
            if (IntelWarfareSystem.isRadar(base.typeId)) {
                signature *= IntelWarfareSystem.radarMode(world, base).emissionMultiplier;
            }
            if (IntelWarfareSystem.isJammer(base.typeId)) signature *= 2.25;
            if (!base.productionQueue.isEmpty()) signature += 0.30;
            return signature;
        }

        private boolean focusedRadarSensor(IntelWarfareSystem.IntelSensor sensor) {
            if (world == null || sensor == null || sensor.sourceKey() == null || !sensor.sourceKey().startsWith("B:")) {
                return false;
            }
            Base base = world.bases.get(sensor.sourceKey().substring(2));
            return base != null && IntelWarfareSystem.isRadar(base.typeId)
                    && StationControls.radarSearchTarget(world, base) == StationControls.RadarSearchTarget.WORMHOLES;
        }

        private void addFocusedWormholeSensors(List<Sensor> found) {
            if (world == null || found == null || world.wormholes.isEmpty()) return;
            for (WormholeGate gate : world.wormholes) {
                if (gate == null || !StationControls.focusedWormholeSearchCovers(world, playerId, gate.x, gate.y)) continue;
                String gateId = gate.id == null || gate.id.isBlank()
                        ? gate.toSystemId + ':' + Math.round(gate.x) + ':' + Math.round(gate.y) : gate.id;
                found.add(sensor("W:" + gateId, gate.x, gate.y, WORMHOLE_DISCOVERY_RADIUS));
            }
        }

        private Sensor sensor(String sourceKey, double x, double y, double range) {
            double safeRange = Double.isFinite(range) ? Math.max(0, range) : 0;
            String key = sourceKey == null || sourceKey.isBlank()
                    ? "S:" + Double.doubleToLongBits(x) + ':' + Double.doubleToLongBits(y) + ':'
                    + Double.doubleToLongBits(safeRange)
                    : sourceKey;
            return new Sensor(key, x, y, safeRange, safeRange * safeRange);
        }
    }

    record Sensor(String sourceKey, double x, double y, double range, double rangeSquared) { }
    private record ResourceRadar(double x, double y, double range, int surveyPower) { }
    private record ResourceHarvester(double x, double y, double identifiedRange, double detailedRange,
                                     Set<NodeKind> harvestKinds) { }
}
