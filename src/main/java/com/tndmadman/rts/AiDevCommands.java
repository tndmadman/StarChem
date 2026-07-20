package com.tndmadman.rts;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.geom.Rectangle2D;

final class AiDevCommands {
    private static final double GIVE_AMOUNT = 1000.0;

    private AiDevCommands() { }

    static void spawnCorsairs(World world) {
        NpcFaction f = AiDevSnapshot.corsairs();
        if (f == null) { world.status = "Corsair faction is not configured."; return; }
        if (NpcFactionSpawner.spawn(world, f, NpcSpawnReason.FORCED)) return;
        world.status = "Corsair Syndicate is already active somewhere in the galaxy.";
        AiDevLog.add(world, f, "spawn skipped: already active");
    }

    static void killCorsairs(World world) {
        NpcFaction f = AiDevSnapshot.corsairs();
        if (f == null) return;
        String previousSystemId = world.activeSystemId();
        GalaxyMapSnapshot snapshot = world.authoritativeGalaxyMapSnapshot();
        int removed = 0;
        try {
            for (GalaxyMapSystem system : snapshot.systems()) {
                if (system == null || system.id() == null || system.id().isBlank()) continue;
                world.activateSystem(system.id());
                int unitsBefore = world.units.size();
                int basesBefore = world.bases.size();
                world.units.values().removeIf(u -> u.playerId.equals(f.id()));
                world.bases.values().removeIf(b -> b.playerId.equals(f.id()));
                removed += unitsBefore - world.units.size();
                removed += basesBefore - world.bases.size();
                world.saveActiveSystem();
            }
        } finally {
            if (previousSystemId != null && !previousSystemId.isBlank()) world.activateSystem(previousSystemId);
        }
        NpcStrategicDirector.onDefeated(world, f);
        world.status = "Dev killed all Corsairs across the galaxy.";
        AiDevLog.add(world, f, "killed/reset " + removed + " asset(s) across galaxy");
    }

    static void resetCorsairs(World world) { killCorsairs(world); spawnCorsairs(world); }

    static void giveCorsairResources(World world) {
        NpcFaction f = AiDevSnapshot.corsairs();
        if (f == null) return;
        if (!world.hasLiveAssets(f.id())) spawnCorsairs(world);
        String previousSystemId = world.activeSystemId();
        try {
            world.activateSystem(NpcFactionRuntime.homeSystemIdFor(f));
            Base b = firstBase(world, f.id());
            if (b == null) return;
            for (Material m : Material.values()) HangarStore.add(b.inventory, m, GIVE_AMOUNT);
            world.saveActiveSystem();
        } finally {
            if (previousSystemId != null && !previousSystemId.isBlank()) world.activateSystem(previousSystemId);
        }
        world.status = "Dev gave Corsairs resources.";
        AiDevLog.add(world, f, "gave all resources");
    }

    static void givePlayerResources(World world) {
        Base b = world.nearestBase(PlayerRegistry.localId(), world.width / 2.0, world.height / 2.0);
        if (b == null) return;
        for (Material m : Material.values()) HangarStore.add(b.inventory, m, GIVE_AMOUNT);
        world.status = "Dev gave player resources.";
        AiDevLog.add("DEV", "gave player resources");
    }

    static void spawnLootField(World world) {
        double cx = world.width * 0.52, cy = world.height * 0.48;
        int i = 0;
        for (Material m : Material.values()) {
            double a = i++ * 0.65;
            world.addWorldItem(m, 80, cx + Math.cos(a) * 160, cy + Math.sin(a) * 160, 0, 0, a, 0.02);
        }
        world.status = "Dev spawned loot field.";
        AiDevLog.add("DEV", "spawned loot field");
    }

    static void spawnAttackWave(World world) {
        String id = Config.RAIDERS_ID;
        PlayerRegistry.register(id, "Raiders", 0xFF5F55, false);
        Rectangle2D local = world.localBounds();
        double x = local == null ? world.width * 0.25 : Calc.clamp(local.getCenterX() - 900, 200, world.width - 200);
        double y = local == null ? world.height * 0.25 : Calc.clamp(local.getCenterY() - 500, 200, world.height - 200);
        int n = nextUnitNumber(world, id);
        for (String ship : new String[]{"frigate", "frigate", "destroyer"}) if (Rules.SHIPS.containsKey(ship)) {
            Unit u = new Unit(id, n++, ship, x + n * 45, y + n * 30);
            String target = nearestLocalTarget(world, u.x, u.y);
            if (!target.isBlank()) u.attack(target);
            world.units.put(u.key(), u);
        }
        world.status = "Dev spawned enemy attack wave.";
        AiDevLog.add("DEV", "spawned enemy attack wave");
    }

    static void forceRaid(World world) {
        NpcFaction f = AiDevSnapshot.corsairs(); if (f == null) return;
        String target = nearestLocalTarget(world, world.width / 2.0, world.height / 2.0);
        int count = 0;
        for (Unit u : world.units.values()) if (u.playerId.equals(f.id()) && WeaponRules.armed(u.type()) && !target.isBlank()) { u.attack(target); count++; }
        world.status = "Dev forced Corsair raid with " + count + " ship(s).";
        AiDevLog.add(world, f, "forced raid at " + target);
    }

    static void forceStation(World world) {
        NpcFaction f = AiDevSnapshot.corsairs();
        if (f == null) return;
        String previousSystemId = world.activeSystemId();
        try {
            world.activateSystem(NpcFactionRuntime.homeSystemIdFor(f));
            Base source = firstBase(world, f.id());
            if (source == null) {
                world.status = "Cannot deploy a Corsair station: no Corsair station exists.";
                return;
            }

            NpcFactionCapacitySnapshot capacity = NpcFactionCapacitySystem.snapshot(world, f);
            if (f.maxStations() <= 0 || capacity.stationCommitments() >= f.maxStations()) {
                world.status = "Dev station skipped: Corsairs are at station cap "
                        + capacity.stationCommitments() + "/" + f.maxStations() + ".";
                AiDevLog.add(world, f, "dev station skipped at global cap");
                return;
            }
            if (NpcStationConstructionSystem.hasAnyActivePlan(world, f)) {
                world.status = "Corsair station deployment is already active.";
                return;
            }

            Unit builder = availableBuilder(world, f);
            if (builder == null) {
                int id = nextUnitNumber(world, f.id());
                builder = new Unit(f.id(), id, "station_builder",
                        Calc.clamp(source.x + source.type().buildRadius + 70, 0, world.width),
                        source.y);
                world.units.put(builder.key(), builder);
                AiDevLog.add(world, f, "dev created station deployer #" + builder.unitId);
            }

            String type = validLoadedPackage(builder.basePackageType)
                    ? builder.basePackageType : nextStationType(world, f, capacity);
            if (type.isBlank()) {
                world.status = "Cannot deploy a Corsair station: no valid package type.";
                return;
            }
            builder.basePackageType = type;
            NpcStationDeployerRecoverySystem.park(builder);
            boolean started = NpcStationConstructionSystem.startLoaded(world, f, builder, type);
            world.saveActiveSystem();
            if (started) {
                world.status = "Dev started timed Corsair station deployment: " + type + ".";
                AiDevLog.add(world, f, "dev started timed station deployment " + type);
            } else {
                world.status = "Corsair deployer is loaded with " + type
                        + " but no viable construction site is available yet.";
                AiDevLog.add(world, f, "dev deployer waiting for viable site for " + type);
            }
        } finally {
            if (previousSystemId != null && !previousSystemId.isBlank()) world.activateSystem(previousSystemId);
        }
    }

    static void forceResearch(World world) {
        NpcFaction f = AiDevSnapshot.corsairs(); if (f == null) return;
        for (String id : f.researchTopicIds()) {
            if (!world.hasResearch(f.id(), id)) { world.completeResearch(f.id(), id); AiDevLog.add(world, f, "forced research " + id); world.status = "Dev completed Corsair research: " + id; return; }
        }
        world.status = "Corsair research already complete.";
    }

    static void forceCraft(World world) {
        NpcFaction f = AiDevSnapshot.corsairs();
        if (f == null) return;
        String previousSystemId = world.activeSystemId();
        try {
            world.activateSystem(NpcFactionRuntime.homeSystemIdFor(f));
            Base b = firstBase(world, f.id());
            if (b == null) return;
            HangarStore.add(b.inventory, Material.FUEL, 100);
            world.saveActiveSystem();
            world.status = "Dev crafted Corsair fuel.";
            AiDevLog.add(world, f, "forced craft fuel");
        } finally {
            if (previousSystemId != null && !previousSystemId.isBlank()) world.activateSystem(previousSystemId);
        }
    }

    static void copySnapshot(World world) {
        String text = AiDevSnapshot.copySnapshot(world);
        try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null); world.status = "AI debug snapshot copied."; }
        catch (Exception ex) { world.status = text.length() > 120 ? text.substring(0, 120) : text; }
        AiDevLog.add("DEV", "copied AI snapshot");
    }

    static void hotReload(World world) {
        world.aiDevSettings.hotReloadRequested = true;
        world.status = "AI config hot reload requested. Restart still safest if parser shape changed.";
        AiDevLog.add("DEV", "hot reload requested");
    }

    private static Unit availableBuilder(World world, NpcFaction faction) {
        Unit empty = null;
        for (Unit unit : world.units.values()) {
            if (!faction.id().equals(unit.playerId) || unit.hp <= 0
                    || !unit.type().baseBuilder) continue;
            if (NpcStationConstructionSystem.ownsBuilder(world, unit.key())
                    || NpcExpeditionSystem.ownsUnit(world, unit.key())
                    || NpcRecoverySystem.ownsUnit(world, unit)
                    || NpcRepairEvacuationSystem.ownsUnit(world, unit)) continue;
            if (!unit.basePackageType.isBlank()) return unit;
            if (empty == null || unit.unitId < empty.unitId) empty = unit;
        }
        return empty;
    }

    private static String nextStationType(World world, NpcFaction faction,
                                          NpcFactionCapacitySnapshot capacity) {
        for (String candidate : faction.stationPackageTypes()) {
            if (Rules.findBase(candidate) != null
                    && !capacity.hasStationType(world, faction, candidate)) return candidate;
        }
        for (String candidate : faction.stationPackageTypes()) {
            if (Rules.findBase(candidate) != null) return candidate;
        }
        return "";
    }

    private static boolean validLoadedPackage(String type) {
        return type != null && !type.isBlank() && Rules.findBase(type) != null;
    }

    private static Base firstBase(World w, String playerId) { for (Base b : w.bases.values()) if (b.playerId.equals(playerId)) return b; return null; }
    private static int nextUnitNumber(World w, String playerId) { int max = 0; for (Unit u : w.units.values()) if (u.playerId.equals(playerId)) max = Math.max(max, u.unitId); return max + 1; }
    private static String nearestLocalTarget(World world, double x, double y) { Base b = world.nearestBase(PlayerRegistry.localId(), x, y); if (b != null) return CombatTarget.base(b); Unit u = null; double dBest = Double.MAX_VALUE; for (Unit t : world.units.values()) if (PlayerRegistry.isLocal(t.playerId)) { double d = Calc.distance(x, y, t.x, t.y); if (d < dBest) { u = t; dBest = d; } } return u == null ? "" : CombatTarget.unit(u); }
}
