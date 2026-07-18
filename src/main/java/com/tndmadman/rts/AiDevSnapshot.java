package com.tndmadman.rts;

import java.awt.*;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

final class AiDevSnapshot {
    private AiDevSnapshot() { }

    static NpcFaction corsairs() {
        for (NpcFaction f : NpcRules.factions()) if ("NPC_CORSAIRS".equals(f.id())) return f;
        return null;
    }

    static String factionState(World world, NpcFaction faction) {
        if (faction == null) return "NO FACTION";
        if (!NpcStrategicDirector.initialized(world, faction)) {
            return world.hasLiveAssets(faction.id()) ? "ACTIVE (REMOTE)" : "DEFEATED";
        }
        return NpcStrategicDirector.state(world, faction).name();
    }

    static List<String> summary(World world, NpcFaction faction) {
        List<String> out = new ArrayList<>();
        if (faction == null) { out.add("No Corsair faction loaded."); return out; }
        NpcStrategicSnapshot strategic = NpcStrategicDirector.snapshot(world, faction);
        NpcFactionCapacitySnapshot capacity = NpcFactionCapacitySystem.snapshot(world, faction);
        int workers = strategic.hasAssets() ? strategic.workers() : capacity.workers();
        int combat = strategic.hasAssets() ? strategic.combat() : capacity.combat();
        int support = strategic.hasAssets() ? strategic.support() : capacity.support();
        int stations = strategic.hasAssets() ? strategic.stations() : capacity.livingStations();
        out.add(faction.name() + " | " + factionState(world, faction) + " | " + NpcDifficultyPreset.current().label);
        out.add("Workers " + workers + "/" + faction.maxWorkers()
                + " | Combat " + combat + "/" + faction.targetFleetSize()
                + " | Support " + support + "/" + faction.maxSupportUnits()
                + " | Stations " + stations + "/" + faction.maxStations());
        if (capacity.stationCommitments() != stations) {
            out.add("Station commitments " + capacity.stationCommitments()
                    + " = living " + capacity.livingStations()
                    + " + queued " + capacity.queuedStationPackages()
                    + " + building " + capacity.activeConstructionPlans()
                    + " + expedition " + capacity.committedExpeditionFootholds());
        }
        if (strategic.hasAssets()) {
            out.add("Systems " + strategic.systemsWithAssets() + " | Controlled " + strategic.controlledSystems()
                    + " | Threats " + strategic.threats() + " | Fuel " + (int)strategic.fuel());
        }
        appendExpedition(out, world, faction);
        appendDeployer(out, world, faction, capacity);
        if (!NpcStrategicDirector.initialized(world, faction) && world.hasLiveAssets(faction.id())) {
            out.add("State source: synchronized remote assets; host strategy state is not replicated.");
        }
        out.add("Need: " + nextNeed(world, faction));
        out.add("Resources: " + resourceLine(world, faction, 6));
        return out;
    }

    static String blockedReason(World world, NpcFaction faction) {
        if (faction == null) return "No faction.";
        NpcFactionCapacitySnapshot capacity = NpcFactionCapacitySystem.snapshot(world, faction);
        if (faction.maxStations() > 0
                && capacity.stationCommitments() >= faction.maxStations()) {
            return "Station cap reached " + capacity.stationCommitments()
                    + "/" + faction.maxStations() + "; expansion disabled.";
        }
        String readiness = NpcExpeditionReadinessSystem.status(world, faction);
        if (!readiness.isBlank()) return "Expedition: " + readiness;
        ResearchTopic missing = missingResearch(world, faction);
        if (missing != null) return "Research needed: " + missing.name + " | Missing: " + missingCost(world, faction, missing.requiredResources);
        for (String ship : faction.fleetUnitTypes()) {
            if (!ResearchRules.shipUnlocked(world, faction.id(), ship)) return "Cannot build " + ship + ": research locked.";
            ShipType t = Rules.ship(ship);
            if (t != null && !canAfford(world, faction, t.buildCost)) return "Cannot build " + ship + ": missing " + missingCost(world, faction, t.buildCost);
        }
        return "No obvious block.";
    }

    static String copySnapshot(World world) {
        NpcFaction f = corsairs();
        StringBuilder b = new StringBuilder();
        b.append("Corsair AI snapshot\n");
        for (String line : summary(world, f)) b.append(line).append('\n');
        b.append("Blocked: ").append(blockedReason(world, f)).append('\n');
        b.append("Bases:\n");
        for (Base base : world.bases.values()) if (f != null && base.playerId.equals(f.id())) {
            b.append("- ").append(base.id).append(' ').append(base.type().name).append(" hp=").append((int)base.hp).append(" inv=").append(inv(base.inventory)).append('\n');
        }
        b.append("Units:\n");
        for (Unit unit : world.units.values()) if (f != null && unit.playerId.equals(f.id())) {
            b.append("- ").append(unit.shipTypeId).append(" #").append(unit.unitId).append(' ').append(unit.task)
                    .append(" hp=").append((int)unit.hp).append(" cargo=").append((int)unit.cargoUsed()).append('/').append((int)unit.type().cargoCapacity);
            if (!unit.basePackageType.isBlank()) b.append(" package=").append(unit.basePackageType);
            if (unit.task == UnitTask.MOVE) b.append(" move=").append(coordinate(unit.targetX, unit.targetY));
            if (!unit.attackTarget.isBlank()) b.append(" target=").append(unit.attackTarget);
            b.append('\n');
        }
        return b.toString();
    }

    static void drawLabel(Graphics2D g2, World world, NpcFaction f, double x, double y) {
        List<String> lines = summary(world, f);
        g2.setColor(new Color(0, 0, 0, 180));
        g2.fillRoundRect((int)x - 8, (int)y - 18, 560, 18 + lines.size() * 15, 12, 12);
        g2.setColor(new Color(f.rgb()));
        int yy = (int)y;
        for (String line : lines) { g2.drawString(line, (int)x, yy); yy += 15; }
    }

    private static void appendExpedition(List<String> out, World world, NpcFaction faction) {
        NpcExpeditionSnapshot expedition = NpcExpeditionSystem.snapshot(world, faction);
        if (!expedition.active()) return;
        StringBuilder line = new StringBuilder("Expedition: ")
                .append(expedition.state());
        if (!expedition.sourceSystemId().isBlank() || !expedition.targetSystemId().isBlank()) {
            line.append(' ').append(expedition.sourceSystemId())
                    .append(" -> ").append(expedition.targetSystemId());
        }
        if (!expedition.route().isEmpty()) {
            line.append(" hop ")
                    .append(Math.min(expedition.routeIndex() + 1, expedition.route().size()))
                    .append('/').append(expedition.route().size());
        }
        if (!expedition.reason().isBlank()) line.append(" | ").append(expedition.reason());
        String readiness = NpcExpeditionReadinessSystem.status(world, faction);
        if (!readiness.isBlank()) line.append(" | ").append(readiness);
        out.add(line.toString());
    }

    private static void appendDeployer(List<String> out, World world, NpcFaction faction,
                                       NpcFactionCapacitySnapshot capacity) {
        NpcStationConstructionSnapshot construction =
                NpcStationConstructionSystem.snapshot(world, faction);
        if (construction.active()) {
            Unit builder = world.units.get(construction.builderKey());
            String id = builder == null ? construction.builderKey()
                    : "#" + builder.unitId;
            if (construction.waitingForSite()) {
                out.add("Deployer " + id + ": loaded " + construction.packageType()
                        + ", waiting for a viable site; replans " + construction.replans());
            } else if (construction.phase() == NpcConstructionPhase.TRAVELLING) {
                double distance = builder == null ? -1
                        : Calc.distance(builder.x, builder.y,
                        construction.targetX(), construction.targetY());
                out.add("Deployer " + id + ": travelling with " + construction.packageType()
                        + (distance < 0 ? "" : ", " + (int)Math.round(distance) + " from site")
                        + " -> " + coordinate(construction.targetX(), construction.targetY()));
            } else {
                out.add("Deployer " + id + ": constructing " + construction.packageType()
                        + ", " + (int)Math.ceil(construction.remaining()) + "s remaining at "
                        + coordinate(construction.targetX(), construction.targetY()));
            }
            return;
        }

        boolean found = false;
        for (Unit unit : world.units.values()) {
            if (!faction.id().equals(unit.playerId) || unit.hp <= 0
                    || !unit.type().baseBuilder) continue;
            found = true;
            if (!unit.basePackageType.isBlank()) {
                if (faction.maxStations() > 0
                        && capacity.stationCommitments() >= faction.maxStations()) {
                    out.add("Deployer #" + unit.unitId + ": loaded " + unit.basePackageType
                            + " and parked; station cap " + capacity.stationCommitments()
                            + "/" + faction.maxStations());
                } else if (unit.task == UnitTask.MOVE) {
                    out.add("Deployer #" + unit.unitId + ": loaded " + unit.basePackageType
                            + ", moving toward " + coordinate(unit.targetX, unit.targetY));
                } else {
                    out.add("Deployer #" + unit.unitId + ": loaded " + unit.basePackageType
                            + "; construction plan recovery pending");
                }
            } else if (faction.maxStations() > 0
                    && capacity.stationCommitments() >= faction.maxStations()) {
                out.add("Deployer #" + unit.unitId + ": empty and parked; station cap "
                        + capacity.stationCommitments() + "/" + faction.maxStations());
            } else {
                out.add("Deployer #" + unit.unitId + ": empty and parked; waiting for a station order");
            }
        }
        if (!found) out.add("Deployer: none available");
    }

    private static boolean needsFuel(World world, NpcFaction f) { return f.fuelReserve() > 0 && material(world, f, Material.FUEL) < f.fuelReserve(); }

    private static ResearchTopic missingResearch(World world, NpcFaction f) {
        for (String id : f.researchTopicIds()) {
            ResearchTopic t = ResearchRules.topic(id);
            if (t != null && !world.hasResearch(f.id(), id)) return t;
        }
        return null;
    }

    private static String nextNeed(World world, NpcFaction f) {
        NpcFactionCapacitySnapshot capacity = NpcFactionCapacitySystem.snapshot(world, f);
        if (f.maxStations() > 0 && capacity.stationCommitments() >= f.maxStations()) {
            return "hold territory; station cap " + capacity.stationCommitments()
                    + "/" + f.maxStations() + " blocks expansion";
        }
        if (needsFuel(world, f)) return "fuel reserve " + (int)material(world, f, Material.FUEL) + "/" + (int)f.fuelReserve();
        ResearchTopic t = missingResearch(world, f);
        if (t != null) return t.name + " -> " + missingCost(world, f, t.requiredResources);
        return blockedReason(world, f);
    }

    private static double material(World world, NpcFaction f, Material m) { double total = 0; for (Base b : world.bases.values()) if (b.playerId.equals(f.id())) total += b.inventory.getOrDefault(m, 0.0); return total; }
    private static boolean canAfford(World world, NpcFaction f, List<Cost> cost) { for (Cost c : cost) if (material(world, f, c.material()) + 0.01 < c.amount()) return false; return true; }

    private static String missingCost(World world, NpcFaction f, List<Cost> cost) {
        for (Cost c : cost) { double have = material(world, f, c.material()); if (have + 0.01 < c.amount()) return c.material().label + " " + (int)have + "/" + (int)c.amount(); }
        return "none";
    }

    private static String resourceLine(World world, NpcFaction f, int max) {
        int shown = 0; StringBuilder b = new StringBuilder();
        for (Material m : Material.values()) {
            double v = material(world, f, m); if (v <= 0.05) continue;
            if (shown++ > 0) b.append(", ");
            b.append(m.name()).append(' ').append((int)v);
            if (shown >= max) break;
        }
        return b.length() == 0 ? "empty" : b.toString();
    }

    private static String inv(EnumMap<Material, Double> inv) {
        if (inv.isEmpty()) return "empty";
        StringBuilder b = new StringBuilder();
        for (Map.Entry<Material, Double> e : inv.entrySet()) {
            if (b.length() > 0) b.append(',');
            b.append(e.getKey().name()).append('=').append((int)Math.round(e.getValue()));
        }
        return b.toString();
    }

    private static String coordinate(double x, double y) {
        return "(" + (int)Math.round(x) + "," + (int)Math.round(y) + ")";
    }
}
