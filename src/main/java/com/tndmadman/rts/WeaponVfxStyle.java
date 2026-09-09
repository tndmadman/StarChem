package com.tndmadman.rts;

/** Cosmetic weapon presentation families. Gameplay behavior remains owned by WeaponSystem. */
enum WeaponVfxStyle {
    PULSE,
    BEAM,
    PROJECTILE,
    HEAVY_PROJECTILE,
    POINT_DEFENSE;

    static WeaponVfxStyle forWeapon(WeaponType weapon) {
        if (weapon == null) return PULSE;
        if (weapon.screenWeapon) return POINT_DEFENSE;
        if (weapon.beam) return BEAM;
        if (weapon.movingShot) {
            return weapon.stoppable || weapon.damage >= 150 ? HEAVY_PROJECTILE : PROJECTILE;
        }
        return PULSE;
    }
}
