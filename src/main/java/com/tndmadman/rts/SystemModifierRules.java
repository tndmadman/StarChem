package com.tndmadman.rts;

import java.util.ArrayList;

final class SystemModifierRules {
    private SystemModifierRules() { }

    static SystemModifiers current(World world) {
        if (world == null) return SystemModifiers.STANDARD;
        StarSystemDefinition definition = StarSystems.get(world.activeSystemId());
        SystemModifiers base = definition == null ? SystemModifiers.STANDARD : definition.modifiers();
        SystemModifiers legacy = GalaxyEventDirector.temporaryModifiers(world, world.activeSystemId());
        SystemModifiers advanced = GalaxyEventExtensions.temporaryModifiers(world, world.activeSystemId());
        return new SystemModifiers(
                base.miningYield() * legacy.miningYield() * advanced.miningYield(),
                base.resourceRespawn() * legacy.resourceRespawn() * advanced.resourceRespawn(),
                base.sensorRange() * legacy.sensorRange() * advanced.sensorRange(),
                base.shieldRegen() * legacy.shieldRegen() * advanced.shieldRegen(),
                base.movementSpeed() * legacy.movementSpeed() * advanced.movementSpeed(),
                base.weaponRange() * legacy.weaponRange() * advanced.weaponRange(),
                base.environmentalDamagePerSecond()
                        + legacy.environmentalDamagePerSecond()
                        + advanced.environmentalDamagePerSecond());
    }

    static double miningYield(World world) { return current(world).miningYield(); }
    static double resourceRespawn(World world) { return current(world).resourceRespawn(); }
    static double sensorRange(World world) { return current(world).sensorRange(); }
    static double shieldRegen(World world) { return current(world).shieldRegen(); }
    static double movementSpeed(World world) { return current(world).movementSpeed(); }
    static double weaponRange(World world) { return current(world).weaponRange(); }

    static void applyEnvironment(World world, double dt) {
        if (world == null || dt <= 0) return;
        GalaxyEventDirector.update(world, dt);
        GalaxyEventExtensions.update(world, dt);
        double damage = current(world).environmentalDamagePerSecond() * dt;
        if (damage <= 0) return;
        for (Unit unit : new ArrayList<>(world.units.values())) {
            if (unit.hp > 0) ShieldSystem.damage(unit, damage);
        }
        for (Base base : new ArrayList<>(world.bases.values())) {
            if (base.hp > 0) ShieldSystem.damage(base, damage);
        }
    }
}
