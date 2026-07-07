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
        if (threatNearBase(world, faction)) return "DEFENDING";
        if (missingResearch(world, faction) != null) return "RESEARCHING";
        if (needsFuel(world, faction)) return "CRAFTING_FUEL";
        if (baseCount(world, faction) < faction.maxStations()) return "BUILDING_STATION";
        if (combatCount(world, faction) >= Math.max(1, faction.raidFleetSize())) return "RAID_READY";
        if (workerCount(world, faction) < faction.maxWorkers()) return "MINING";
        return "BUILDING_FLEET";
    }

    static List<String> summary(World world, NpcFaction faction) {
        List<String> out = new ArrayList<>();
        if (faction == null) { out.add("No Corsair faction loaded."); return out; }
        out.add(faction.name() + " | " + factionState(world, faction) + " | " + NpcDifficultyPreset.current().label);
        out.add("Workers " + workerCount(world, faction) + "/" + faction.maxWorkers()
                + " | Combat " + combatCount(world, faction) + "/" + faction.targetFleetSize()
                + " | Support " + supportCount(world, faction) + "/" + faction.maxSupportUnits()
                + " | Stations " + baseCount(world, faction) + "/" + faction.maxStations());
        out.add("Need: " + nextNeed(world, faction));
        out.add("Resources: " + resourceLine(world, faction, 6));
        return out;
    }

    static String blockedReason(World world, NpcFaction faction) {
        if (faction == null) return "No faction.";
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
            if (!unit.attackTarget.isBlank()) b.append(" target=").append(unit.attackTarget);
            b.append('\n');
        }
        return b.toString();
    }

    static void drawLabel(Graphics2D g2, World world, NpcFaction f, double x, double y) {
        List<String> lines = summary(world, f);
        g2.setColor(new Color(0, 0, 0, 180));
        g2.fillRoundRect((int)x - 8, (int)y - 18, 320, 18 + lines.size() * 15, 12, 12);
        g2.setColor(new Color(f.rgb()));
        int yy = (int)y;
        for (String line : lines) { g2.drawString(line, (int)x, yy); yy += 15; }
    }

    private static boolean threatNearBase(World world, NpcFaction f) {
        for (Base b : world.bases.values()) if (b.playerId.equals(f.id())) {
            for (Unit u : world.units.values()) if (!u.playerId.equals(f.id()) && !NpcRules.isNpcFaction(u.playerId) && Calc.distance(b.x, b.y, u.x, u.y) < f.defendRange()) return true;
        }
        return false;
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
        if (needsFuel(world, f)) return "fuel reserve " + (int)material(world, f, Material.FUEL) + "/" + (int)f.fuelReserve();
        ResearchTopic t = missingResearch(world, f);
        if (t != null) return t.name + " -> " + missingCost(world, f, t.requiredResources);
        return blockedReason(world, f);
    }

    private static int baseCount(World world, NpcFaction f) { int c = 0; for (Base b : world.bases.values()) if (b.playerId.equals(f.id()) && b.hp > 0) c++; return c; }
    private static int workerCount(World world, NpcFaction f) { int c = 0; for (Unit u : world.units.values()) if (u.playerId.equals(f.id()) && u.hp > 0 && !u.type().harvestKinds.isEmpty()) c++; return c; }
    private static int combatCount(World world, NpcFaction f) { int c = 0; for (Unit u : world.units.values()) if (u.playerId.equals(f.id()) && u.hp > 0 && WeaponRules.armed(u.type())) c++; return c; }
    private static int supportCount(World world, NpcFaction f) { int c = 0; for (Unit u : world.units.values()) if (u.playerId.equals(f.id()) && u.hp > 0 && f.supportTypeSet().contains(u.shipTypeId)) c++; return c; }
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
}
