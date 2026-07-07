package com.tndmadman.rts;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.geom.Rectangle2D;

final class AiDevCommands {
    private static final double GIVE_AMOUNT = 1000.0;

    private AiDevCommands() { }

    static void spawnCorsairs(World world) {
        NpcFaction f = AiDevSnapshot.corsairs();
        if (f == null || hasAssets(world, f.id())) { AiDevLog.add(world, f, "spawn skipped: already active"); return; }
        Rectangle2D local = world.localBounds();
        double x = local == null ? world.width * 0.65 : Calc.clamp(local.getCenterX() + f.spawnDistance(), 600, world.width - 600);
        double y = local == null ? world.height * 0.55 : Calc.clamp(local.getCenterY() + f.spawnDistance() * 0.35, 600, world.height - 600);
        String baseId = f.id() + ":B" + nextBaseNumber(world, f.id());
        world.bases.put(baseId, new Base(baseId, f.id(), f.baseType(), x, y));
        int n = nextUnitNumber(world, f.id());
        for (String ship : f.startingUnits()) if (Rules.SHIPS.containsKey(ship)) {
            Unit u = new Unit(f.id(), n++, ship, x + 120 + n * 20, y + 80);
            world.units.put(u.key(), u);
        }
        PlayerRegistry.register(f.id(), f.name(), f.rgb(), false);
        world.status = "Dev spawned Corsair Syndicate.";
        AiDevLog.add(world, f, "forced spawn");
    }

    static void killCorsairs(World world) {
        NpcFaction f = AiDevSnapshot.corsairs(); if (f == null) return;
        world.units.values().removeIf(u -> u.playerId.equals(f.id()));
        world.bases.values().removeIf(b -> b.playerId.equals(f.id()));
        world.status = "Dev killed all Corsairs.";
        AiDevLog.add(world, f, "killed/reset all assets");
    }

    static void resetCorsairs(World world) { killCorsairs(world); spawnCorsairs(world); }

    static void giveCorsairResources(World world) {
        NpcFaction f = AiDevSnapshot.corsairs(); Base b = firstBase(world, f == null ? "" : f.id());
        if (b == null) { spawnCorsairs(world); b = firstBase(world, f.id()); }
        if (b == null) return;
        for (Material m : Material.values()) HangarStore.add(b.inventory, m, GIVE_AMOUNT);
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
        NpcFaction f = AiDevSnapshot.corsairs(); Base b = firstBase(world, f == null ? "" : f.id());
        if (f == null || b == null) return;
        String type = "shipyard";
        for (String candidate : f.stationPackageTypes()) if (!hasBaseType(world, f.id(), candidate)) { type = candidate; break; }
        int n = nextBaseNumber(world, f.id());
        double a = n * 2.2;
        world.bases.put(f.id() + ":B" + n, new Base(f.id() + ":B" + n, f.id(), type, b.x + Math.cos(a) * f.stationSpacing(), b.y + Math.sin(a) * f.stationSpacing()));
        world.status = "Dev forced Corsair station: " + type;
        AiDevLog.add(world, f, "forced station " + type);
    }

    static void forceResearch(World world) {
        NpcFaction f = AiDevSnapshot.corsairs(); if (f == null) return;
        for (String id : f.researchTopicIds()) {
            if (!world.hasResearch(f.id(), id)) { world.completeResearch(f.id(), id); AiDevLog.add(world, f, "forced research " + id); world.status = "Dev completed Corsair research: " + id; return; }
        }
        world.status = "Corsair research already complete.";
    }

    static void forceCraft(World world) {
        NpcFaction f = AiDevSnapshot.corsairs(); Base b = firstBase(world, f == null ? "" : f.id()); if (f == null || b == null) return;
        HangarStore.add(b.inventory, Material.FUEL, 100);
        world.status = "Dev crafted Corsair fuel.";
        AiDevLog.add(world, f, "forced craft fuel");
    }

    static void copySnapshot(World world) {
        String text = AiDevSnapshot.copySnapshot(world);
        try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null); world.status = "AI debug snapshot copied."; }
        catch (Exception ex) { world.status = text.length() > 120 ? text.substring(0, 120) : text; }
        AiDevLog.add("DEV", "copied AI snapshot");
    }

    static void hotReload(World world) {
        AiDevSettings.hotReloadRequested = true;
        world.status = "AI config hot reload requested. Restart still safest if parser shape changed.";
        AiDevLog.add("DEV", "hot reload requested");
    }

    private static boolean hasAssets(World w, String playerId) { for (Unit u : w.units.values()) if (u.playerId.equals(playerId)) return true; for (Base b : w.bases.values()) if (b.playerId.equals(playerId)) return true; return false; }
    private static Base firstBase(World w, String playerId) { for (Base b : w.bases.values()) if (b.playerId.equals(playerId)) return b; return null; }
    private static int nextUnitNumber(World w, String playerId) { int max = 0; for (Unit u : w.units.values()) if (u.playerId.equals(playerId)) max = Math.max(max, u.unitId); return max + 1; }
    private static int nextBaseNumber(World w, String playerId) { int max = 0; String p = playerId + ":B"; for (String id : w.bases.keySet()) if (id.startsWith(p)) try { max = Math.max(max, Integer.parseInt(id.substring(p.length()))); } catch(Exception ignored) { } return max + 1; }
    private static boolean hasBaseType(World w, String playerId, String type) { for (Base b : w.bases.values()) if (b.playerId.equals(playerId) && b.typeId.equals(type)) return true; return false; }
    private static String nearestLocalTarget(World world, double x, double y) { Base b = world.nearestBase(PlayerRegistry.localId(), x, y); if (b != null) return CombatTarget.base(b); Unit u = null; double dBest = Double.MAX_VALUE; for (Unit t : world.units.values()) if (PlayerRegistry.isLocal(t.playerId)) { double d = Calc.distance(x, y, t.x, t.y); if (d < dBest) { u = t; dBest = d; } } return u == null ? "" : CombatTarget.unit(u); }
}
