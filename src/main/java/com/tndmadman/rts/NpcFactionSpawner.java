package com.tndmadman.rts;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

final class NpcFactionSpawner {
    private NpcFactionSpawner() { }

    static boolean spawn(World world, NpcFaction faction) {
        return spawn(world, faction, NpcSpawnReason.NATURAL);
    }

    static boolean spawn(World world, NpcFaction faction, NpcSpawnReason reason) {
        if (world == null || faction == null || reason == null) return false;
        if (world.hasLiveAssets(faction.id())) return false;
        world.resetOrganizedNpcFactionState(faction, false);

        String previousSystemId = world.activeSystemId();
        String homeSystemId = NpcFactionRuntime.homeSystemIdFor(faction);
        boolean spawned = false;
        try {
            if (!homeSystemId.equals(world.activeSystemId())) world.activateSystem(homeSystemId);
            if (!homeSystemId.equals(world.activeSystemId()) || hasLocalAssets(world, faction.id())) return false;

            SpawnPoint point = spawnPoint(world, faction);
            String baseId = faction.id() + ":B" + nextBaseNumber(world, faction.id());
            world.bases.put(baseId, new Base(baseId, faction.id(), validBaseType(faction.baseType()), point.x, point.y));

            List<String> units = validUnitList(faction, faction.startingUnits());
            if (units.isEmpty()) units = fallbackUnits(faction);
            int nextUnit = nextUnitNumber(world, faction.id());
            for (int i = 0; i < units.size(); i++) {
                double angle = i * Math.PI * 2.0 / Math.max(1, units.size());
                double range = faction.unitSpacing() + i * 34;
                Unit unit = new Unit(faction.id(), nextUnit++, units.get(i),
                        Calc.clamp(point.x + Math.cos(angle) * range, 0, world.width),
                        Calc.clamp(point.y + Math.sin(angle) * range, 0, world.height));
                world.units.put(unit.key(), unit);
            }
            world.saveActiveSystem();
            spawned = true;
        } finally {
            if (previousSystemId != null && !previousSystemId.isBlank()
                    && !previousSystemId.equals(world.activeSystemId())) {
                world.activateSystem(previousSystemId);
            }
        }

        if (!spawned) return false;
        NpcStrategicDirector.onSpawned(world, faction);
        PlayerRegistry.register(faction.id(), faction.name(), faction.rgb(), false);
        if (reason == NpcSpawnReason.FORCED) {
            world.status = "Dev spawned " + faction.name() + " in " + homeSystemId + ".";
            AiDevLog.add(world, faction, "forced lifecycle spawn in " + homeSystemId);
        } else {
            world.status = faction.spawnMessage();
            AiDevLog.add(world, faction, "galaxy lifecycle spawn");
        }
        return true;
    }

    private static SpawnPoint spawnPoint(World world, NpcFaction faction) {
        Rectangle2D local = world.localBounds();
        double cx = world.width / 2.0;
        double cy = world.height / 2.0;
        double lx = local == null ? cx : local.getCenterX();
        double ly = local == null ? cy : local.getCenterY();
        double angle = Math.atan2(ly - cy, lx - cx) + Math.PI;
        if (Double.isNaN(angle)) angle = Math.PI * 0.25;
        double distance = Math.max(300.0, faction.spawnDistance());
        double pad = Math.max(0.0, faction.spawnPadding());
        return new SpawnPoint(
                Calc.clamp(lx + Math.cos(angle) * distance, pad, world.width - pad),
                Calc.clamp(ly + Math.sin(angle) * distance, pad, world.height - pad));
    }

    private static List<String> validUnitList(NpcFaction faction, List<String> requestedTypes) {
        List<String> out = new ArrayList<>();
        for (String shipTypeId : requestedTypes) {
            if (!Rules.SHIPS.containsKey(shipTypeId)) continue;
            ShipType ship = Rules.ship(shipTypeId);
            if (faction.behavior() == NpcBehavior.RAIDER && !WeaponRules.armed(ship)) continue;
            if ((faction.behavior() == NpcBehavior.MINER || faction.behavior() == NpcBehavior.FACTION)
                    && ship.harvestKinds.isEmpty() && !WeaponRules.armed(ship)
                    && !isSupportShip(faction, shipTypeId)) continue;
            out.add(shipTypeId);
        }
        return out;
    }

    private static List<String> fallbackUnits(NpcFaction faction) {
        if (faction.behavior() == NpcBehavior.MINER || faction.behavior() == NpcBehavior.FACTION) {
            for (String id : faction.workerUnitTypes()) {
                if (Rules.SHIPS.containsKey(id) && !Rules.ship(id).harvestKinds.isEmpty()) return List.of(id);
            }
            for (ShipType ship : Rules.SHIPS.values()) if (!ship.harvestKinds.isEmpty()) return List.of(ship.id);
            return List.of();
        }
        for (ShipType ship : Rules.SHIPS.values()) if (WeaponRules.armed(ship)) return List.of(ship.id);
        return List.of();
    }

    private static boolean isSupportShip(NpcFaction faction, String shipTypeId) {
        return faction.supportTypeSet().contains(shipTypeId) || "station_builder".equals(shipTypeId);
    }

    private static String validBaseType(String baseType) {
        return Rules.BASES.containsKey(baseType) ? baseType : Rules.DEFAULT_BASE;
    }

    private static boolean hasLocalAssets(World world, String factionId) {
        for (Unit unit : world.units.values()) if (factionId.equals(unit.playerId) && unit.hp > 0) return true;
        for (Base base : world.bases.values()) if (factionId.equals(base.playerId) && base.hp > 0) return true;
        return false;
    }

    private static int nextUnitNumber(World world, String factionId) {
        int max = 0;
        for (Unit unit : world.units.values()) if (factionId.equals(unit.playerId)) max = Math.max(max, unit.unitId);
        return max + 1;
    }

    private static int nextBaseNumber(World world, String factionId) {
        int max = 0;
        String prefix = factionId + ":B";
        for (String id : world.bases.keySet()) {
            if (!id.startsWith(prefix)) continue;
            try { max = Math.max(max, Integer.parseInt(id.substring(prefix.length()))); }
            catch (NumberFormatException ignored) { }
        }
        return max + 1;
    }

    private record SpawnPoint(double x, double y) { }
}

enum NpcSpawnReason { NATURAL, FORCED }
