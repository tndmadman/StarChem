package com.tndmadman.rts;

import java.util.List;

final class NpcStationReplacementSystem {
    private NpcStationReplacementSystem() { }

    static void replaceMissingStations(World world) {
        for (NpcFaction faction : NpcRules.factions()) {
            if (!faction.enabled() || faction.behavior() != NpcBehavior.FACTION) continue;
            if (faction.stationPackageTypes().isEmpty() || livingStationCount(world, faction) >= faction.maxStations()) continue;
            Base source = packageSource(world, faction);
            if (source == null) continue;
            String packageType = missingPackage(world, faction, source);
            if (packageType.isBlank()) continue;
            Unit builder = emptyBuilder(world, faction, source);
            if (builder == null) {
                buildBuilder(world, faction, source);
                AiDevLog.add(world, faction, "station missing; building replacement deployer");
                continue;
            }
            if (!canAfford(world, faction, Rules.base(packageType).buildCost)) {
                AiDevLog.add(world, faction, "station missing; waiting for resources for " + packageType);
                continue;
            }
            spend(world, faction, Rules.base(packageType).buildCost);
            placeReplacement(world, faction, source, builder, packageType);
        }
    }

    private static Base packageSource(World world, NpcFaction faction) {
        for (Base base : world.bases.values()) {
            if (!base.playerId.equals(faction.id()) || base.hp <= 0) continue;
            for (String packageType : faction.stationPackageTypes()) {
                if (Rules.BASES.containsKey(packageType) && base.type().basePackages.contains(packageType)) return base;
            }
        }
        return null;
    }

    private static String missingPackage(World world, NpcFaction faction, Base source) {
        for (String packageType : faction.stationPackageTypes()) {
            if (!Rules.BASES.containsKey(packageType) || !source.type().basePackages.contains(packageType)) continue;
            if (!hasStationType(world, faction, packageType)) return packageType;
        }
        for (String packageType : faction.stationPackageTypes()) {
            if (Rules.BASES.containsKey(packageType) && source.type().basePackages.contains(packageType)) return packageType;
        }
        return "";
    }

    private static Unit emptyBuilder(World world, NpcFaction faction, Base source) {
        Unit best = null;
        double bestDist = Double.MAX_VALUE;
        for (Unit unit : world.units.values()) {
            if (!unit.playerId.equals(faction.id()) || unit.hp <= 0) continue;
            if (!unit.type().baseBuilder || !unit.basePackageType.isBlank()) continue;
            double d = Calc.distance(unit.x, unit.y, source.x, source.y);
            if (d < bestDist) { best = unit; bestDist = d; }
        }
        return best;
    }

    private static void buildBuilder(World world, NpcFaction faction, Base source) {
        String shipType = "station_builder";
        if (!Rules.SHIPS.containsKey(shipType) || !source.type().buildableShips.contains(shipType)) return;
        List<Cost> cost = Rules.ship(shipType).buildCost;
        if (!canAfford(world, faction, cost)) return;
        spend(world, faction, cost);
        int n = nextUnitNumber(world, faction.id());
        Unit unit = new Unit(faction.id(), n, shipType,
                Calc.clamp(source.x + source.type().buildRadius + 55, 0, world.width),
                Calc.clamp(source.y + source.type().buildRadius + 35, 0, world.height));
        world.units.put(unit.key(), unit);
    }

    private static void placeReplacement(World world, NpcFaction faction, Base source, Unit builder, String packageType) {
        int n = nextBaseNumber(world, faction.id());
        double angle = n * 2.35;
        double distance = Math.max(source.type().unloadRange + 220, faction.stationSpacing());
        String id = faction.id() + ":B" + n;
        world.bases.put(id, new Base(id, faction.id(), packageType,
                Calc.clamp(source.x + Math.cos(angle) * distance, 0, world.width),
                Calc.clamp(source.y + Math.sin(angle) * distance, 0, world.height)));
        world.units.remove(builder.key());
        world.status = faction.name() + " replaced lost " + Rules.base(packageType).name + ".";
        AiDevLog.add(world, faction, "replaced lost station with " + packageType);
    }

    private static int livingStationCount(World world, NpcFaction faction) { int c = 0; for (Base b : world.bases.values()) if (b.playerId.equals(faction.id()) && b.hp > 0) c++; return c; }
    private static boolean hasStationType(World world, NpcFaction faction, String type) { for (Base b : world.bases.values()) if (b.playerId.equals(faction.id()) && b.hp > 0 && b.typeId.equals(type)) return true; return false; }
    private static int nextUnitNumber(World world, String playerId) { int max = 0; for (Unit u : world.units.values()) if (u.playerId.equals(playerId)) max = Math.max(max, u.unitId); return max + 1; }
    private static int nextBaseNumber(World world, String playerId) { int max = 0; String p = playerId + ":B"; for (String id : world.bases.keySet()) if (id.startsWith(p)) try { max = Math.max(max, Integer.parseInt(id.substring(p.length()))); } catch (Exception ignored) { } return max + 1; }
    private static double material(World world, NpcFaction faction, Material m) { double total = 0; for (Base b : world.bases.values()) if (b.playerId.equals(faction.id()) && b.hp > 0) total += b.inventory.getOrDefault(m, 0.0); return total; }
    private static boolean canAfford(World world, NpcFaction faction, List<Cost> cost) { for (Cost c : cost) if (material(world, faction, c.material()) + 0.001 < c.amount()) return false; return true; }
    private static void spend(World world, NpcFaction faction, List<Cost> cost) { for (Cost c : cost) spendMaterial(world, faction, c.material(), c.amount()); }
    private static void spendMaterial(World world, NpcFaction faction, Material m, double amount) { double left = amount; for (Base b : world.bases.values()) { if (!b.playerId.equals(faction.id()) || b.hp <= 0 || left <= 0.001) continue; double held = b.inventory.getOrDefault(m, 0.0); double take = Math.min(held, left); if (take <= 0.001) continue; if (held - take <= 0.05) b.inventory.remove(m); else b.inventory.put(m, held - take); left -= take; } }
}
