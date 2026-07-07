package com.tndmadman.rts;

import java.awt.geom.Point2D;
import java.util.EnumMap;

final class NpcHomeSystem {
    private NpcHomeSystem() { }

    static void keepCorsairsHome(World world) {
        Base anchor = firstCorsairBase(world);
        if (anchor == null) return;
        Point2D home = world.npcSpawnPoint(Config.CORSAIRS_ID, 700);
        double dx = home.getX() - anchor.x;
        double dy = home.getY() - anchor.y;
        if (Math.hypot(dx, dy) < 2500) return;
        moveBases(world, dx, dy);
        moveUnits(world, dx, dy);
        world.status = "Corsair Syndicate relocated to its own system.";
    }

    private static Base firstCorsairBase(World world) {
        for (Base base : world.bases.values()) {
            if (Config.CORSAIRS_ID.equals(base.playerId) && base.hp > 0) return base;
        }
        return null;
    }

    private static void moveBases(World world, double dx, double dy) {
        for (Base base : new java.util.ArrayList<>(world.bases.values())) {
            if (!Config.CORSAIRS_ID.equals(base.playerId)) continue;
            Base moved = new Base(base.id, base.playerId, base.typeId,
                    Calc.clamp(base.x + dx, 0, world.width),
                    Calc.clamp(base.y + dy, 0, world.height));
            moved.hp = base.hp;
            moved.shield = base.shield;
            moved.shieldDelayTimer = base.shieldDelayTimer;
            moved.logisticsStatus = base.logisticsStatus;
            copyInventory(base.inventory, moved.inventory);
            world.bases.put(base.id, moved);
        }
    }

    private static void moveUnits(World world, double dx, double dy) {
        for (Unit unit : world.units.values()) {
            if (!Config.CORSAIRS_ID.equals(unit.playerId)) continue;
            unit.x = Calc.clamp(unit.x + dx, 0, world.width);
            unit.y = Calc.clamp(unit.y + dy, 0, world.height);
            unit.targetX = Calc.clamp(unit.targetX + dx, 0, world.width);
            unit.targetY = Calc.clamp(unit.targetY + dy, 0, world.height);
            unit.miningAnchorX = Calc.clamp(unit.miningAnchorX + dx, 0, world.width);
            unit.miningAnchorY = Calc.clamp(unit.miningAnchorY + dy, 0, world.height);
        }
    }

    private static void copyInventory(EnumMap<Material, Double> from, EnumMap<Material, Double> to) {
        to.clear();
        for (Material material : from.keySet()) to.put(material, from.get(material));
    }
}
