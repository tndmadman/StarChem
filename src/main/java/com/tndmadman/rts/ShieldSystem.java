package com.tndmadman.rts;

final class ShieldSystem {
    private ShieldSystem() { }

    static void update(World world, double dt) {
        double regenScale = SystemModifierRules.shieldRegen(world);
        for (Unit unit : world.units.values()) updateUnit(world, unit, dt, regenScale);
        for (Base base : world.bases.values()) updateBase(world, base, dt, regenScale);
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

    private static void updateUnit(World world, Unit unit, double dt, double regenScale) {
        ShipType type = unit.type();
        unit.shield = Calc.clamp(unit.shield, 0, type.maxShield);
        unit.shieldDelayTimer = Math.max(0, unit.shieldDelayTimer - dt);
        if (type.maxShield <= 0 || unit.shieldDelayTimer > 0 || unit.hp <= 0) return;
        unit.shield = Math.min(type.maxShield, unit.shield + type.shieldRegen * regenScale * SystemControlBonuses.shieldRegen(world, unit.playerId) * dt);
    }

    private static void updateBase(World world, Base base, double dt, double regenScale) {
        BaseType type = base.type();
        base.shield = Calc.clamp(base.shield, 0, type.maxShield);
        base.shieldDelayTimer = Math.max(0, base.shieldDelayTimer - dt);
        if (type.maxShield <= 0 || base.shieldDelayTimer > 0 || base.hp <= 0) return;
        base.shield = Math.min(type.maxShield, base.shield + type.shieldRegen * regenScale * SystemControlBonuses.shieldRegen(world, base.playerId) * dt);
    }
}
