package com.tndmadman.rts;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

final class NpcSystem {
    private static final String RAIDER_ID = "NPC_RAIDERS";
    private static final String RAIDER_NAME = "Raiders";
    private static final int RAIDER_COLOR = 0xFF5F55;
    private static final double FIRST_SPAWN_SECONDS = 18.0;
    private static final double RESPAWN_SECONDS = 45.0;
    private static final double ORDER_SECONDS = 2.0;

    private double spawnTimer = FIRST_SPAWN_SECONDS;
    private double orderTimer;

    void update(World world, double dt) {
        PlayerRegistry.register(RAIDER_ID, RAIDER_NAME, RAIDER_COLOR, false);

        if (!hasRaiderAssets(world)) {
            spawnTimer -= dt;
            if (spawnTimer <= 0) {
                spawnRaiders(world);
                spawnTimer = RESPAWN_SECONDS;
            }
            return;
        }

        orderTimer -= dt;
        if (orderTimer <= 0) {
            orderRaiders(world);
            orderTimer = ORDER_SECONDS;
        }
    }

    private boolean hasRaiderAssets(World world) {
        for (Unit unit : world.units.values()) if (unit.playerId.equals(RAIDER_ID) && unit.hp > 0) return true;
        for (Base base : world.bases.values()) if (base.playerId.equals(RAIDER_ID) && base.hp > 0) return true;
        return false;
    }

    private void spawnRaiders(World world) {
        SpawnPoint point = spawnPoint(world);
        String baseId = RAIDER_ID + ":B" + nextBaseNumber(world);
        world.bases.put(baseId, new Base(baseId, RAIDER_ID, Rules.DEFAULT_BASE, point.x, point.y));

        List<String> wave = raiderWave();
        int nextUnit = nextUnitNumber(world);
        for (int i = 0; i < wave.size(); i++) {
            double angle = i * Math.PI * 2.0 / Math.max(1, wave.size());
            double range = 150 + i * 34;
            Unit unit = new Unit(RAIDER_ID, nextUnit++, wave.get(i),
                    Calc.clamp(point.x + Math.cos(angle) * range, 0, world.width),
                    Calc.clamp(point.y + Math.sin(angle) * range, 0, world.height));
            world.units.put(unit.key(), unit);
        }

        world.status = "Raider ships have entered the sector.";
        orderRaiders(world);
    }

    private List<String> raiderWave() {
        List<String> wave = new ArrayList<>();
        addShipIfUsable(wave, "frigate");
        addShipIfUsable(wave, "frigate");
        addShipIfUsable(wave, "destroyer");
        if (wave.isEmpty()) {
            String fallback = firstArmedShip();
            if (!fallback.isBlank()) wave.add(fallback);
        }
        return wave;
    }

    private void addShipIfUsable(List<String> wave, String shipTypeId) {
        if (!Rules.SHIPS.containsKey(shipTypeId)) return;
        if (!WeaponRules.armed(Rules.ship(shipTypeId))) return;
        wave.add(shipTypeId);
    }

    private String firstArmedShip() {
        for (ShipType ship : Rules.SHIPS.values()) {
            if (WeaponRules.armed(ship)) return ship.id;
        }
        return "";
    }

    private SpawnPoint spawnPoint(World world) {
        Rectangle2D local = world.localBounds();
        double cx = world.width / 2.0;
        double cy = world.height / 2.0;
        double lx = local == null ? cx : local.getCenterX();
        double ly = local == null ? cy : local.getCenterY();
        double angle = Math.atan2(ly - cy, lx - cx) + Math.PI;
        if (Double.isNaN(angle)) angle = Math.PI * 0.25;
        double distance = Math.max(2200.0, Math.min(world.width, world.height) * 0.22);
        double x = Calc.clamp(lx + Math.cos(angle) * distance, 700, world.width - 700);
        double y = Calc.clamp(ly + Math.sin(angle) * distance, 700, world.height - 700);
        return new SpawnPoint(x, y);
    }

    private void orderRaiders(World world) {
        for (Unit unit : world.units.values()) {
            if (!unit.playerId.equals(RAIDER_ID) || unit.hp <= 0) continue;
            String target = nearestEnemyTarget(world, unit);
            if (!target.isBlank()) unit.attack(target);
        }
    }

    private String nearestEnemyTarget(World world, Unit unit) {
        String best = "";
        double bestDist = Double.MAX_VALUE;

        for (Base base : world.bases.values()) {
            if (base.playerId.equals(RAIDER_ID) || base.hp <= 0) continue;
            double d = Calc.distance(unit.x, unit.y, base.x, base.y);
            if (d < bestDist) {
                best = CombatTarget.base(base);
                bestDist = d;
            }
        }

        for (Unit target : world.units.values()) {
            if (target.playerId.equals(RAIDER_ID) || target.hp <= 0) continue;
            double d = Calc.distance(unit.x, unit.y, target.x, target.y);
            if (d < bestDist) {
                best = CombatTarget.unit(target);
                bestDist = d;
            }
        }

        return best;
    }

    private int nextUnitNumber(World world) {
        int max = 0;
        for (Unit unit : world.units.values()) if (unit.playerId.equals(RAIDER_ID)) max = Math.max(max, unit.unitId);
        return max + 1;
    }

    private int nextBaseNumber(World world) {
        int max = 0;
        String prefix = RAIDER_ID + ":B";
        for (String id : world.bases.keySet()) {
            if (!id.startsWith(prefix)) continue;
            try { max = Math.max(max, Integer.parseInt(id.substring(prefix.length()))); }
            catch (NumberFormatException ignored) { }
        }
        return max + 1;
    }

    private record SpawnPoint(double x, double y) { }
}
