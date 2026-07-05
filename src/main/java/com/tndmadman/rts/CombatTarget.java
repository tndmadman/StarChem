package com.tndmadman.rts;

final class CombatTarget {
    private CombatTarget() { }

    static String unit(Unit unit) { return unit == null ? "" : "U:" + unit.key(); }
    static String base(Base base) { return base == null ? "" : "B:" + base.id; }

    static Unit unit(World world, String key) {
        if (key == null || !key.startsWith("U:")) return null;
        return world.units.get(key.substring(2));
    }

    static Base base(World world, String key) {
        if (key == null || !key.startsWith("B:")) return null;
        return world.bases.get(key.substring(2));
    }

    static boolean alive(World world, String key) {
        Unit unit = unit(world, key);
        if (unit != null) return unit.hp > 0;
        Base base = base(world, key);
        return base != null && base.hp > 0;
    }

    static boolean enemy(World world, Unit attacker, String key) {
        Unit unit = unit(world, key);
        if (unit != null) return !unit.playerId.equals(attacker.playerId) && unit.hp > 0;
        Base base = base(world, key);
        return base != null && !base.playerId.equals(attacker.playerId) && base.hp > 0;
    }

    static double x(World world, String key) {
        Unit unit = unit(world, key);
        if (unit != null) return unit.x;
        Base base = base(world, key);
        return base == null ? 0 : base.x;
    }

    static double y(World world, String key) {
        Unit unit = unit(world, key);
        if (unit != null) return unit.y;
        Base base = base(world, key);
        return base == null ? 0 : base.y;
    }

    static void damage(World world, String key, double amount) {
        Unit unit = unit(world, key);
        if (unit != null) {
            ShieldSystem.damage(unit, amount);
            return;
        }
        Base base = base(world, key);
        if (base != null) ShieldSystem.damage(base, amount);
    }
}
