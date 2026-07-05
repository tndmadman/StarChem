package com.tndmadman.rts;

final class ShieldSystem {
    private ShieldSystem() { }

    static void update(World world, double dt) {
        for (Unit unit : world.units.values()) updateUnit(unit, dt);
        for (Base base : world.bases.values()) updateBase(base, dt);
    }

    static void damage(Unit unit, double amount) {
        if (unit == null || amount <= 0) return;
        unit.shieldDelayTimer = unit.type().shieldRegenDelay;
        double remaining = amount;
        if (unit.shield > 0) {
            double absorbed = Math.min(unit.shield, remaining);
            unit.shield -= absorbed;
            remaining -= absorbed;
        }
        if (remaining > 0) unit.hp -= remaining;
    }

    static void damage(Base base, double amount) {
        if (base == null || amount <= 0) return;
        base.shieldDelayTimer = base.type().shieldRegenDelay;
        double remaining = amount;
        if (base.shield > 0) {
            double absorbed = Math.min(base.shield, remaining);
            base.shield -= absorbed;
            remaining -= absorbed;
        }
        if (remaining > 0) base.hp -= remaining;
    }

    private static void updateUnit(Unit unit, double dt) {
        ShipType type = unit.type();
        unit.shield = Calc.clamp(unit.shield, 0, type.maxShield);
        unit.shieldDelayTimer = Math.max(0, unit.shieldDelayTimer - dt);
        if (type.maxShield <= 0 || unit.shieldDelayTimer > 0 || unit.hp <= 0) return;
        unit.shield = Math.min(type.maxShield, unit.shield + type.shieldRegen * dt);
    }

    private static void updateBase(Base base, double dt) {
        BaseType type = base.type();
        base.shield = Calc.clamp(base.shield, 0, type.maxShield);
        base.shieldDelayTimer = Math.max(0, base.shieldDelayTimer - dt);
        if (type.maxShield <= 0 || base.shieldDelayTimer > 0 || base.hp <= 0) return;
        base.shield = Math.min(type.maxShield, base.shield + type.shieldRegen * dt);
    }
}
