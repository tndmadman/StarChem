package com.tndmadman.rts;

final class ProjectileShot {
    final int id;
    final String ownerId;
    final String weaponId;
    final String targetKey;
    double x, y, lastX, lastY;

    ProjectileShot(int id, String ownerId, String weaponId, String targetKey, double x, double y) {
        this.id = id;
        this.ownerId = ownerId;
        this.weaponId = weaponId;
        this.targetKey = targetKey;
        this.x = x;
        this.y = y;
        this.lastX = x;
        this.lastY = y;
    }

    WeaponType weapon() { return WeaponRules.WEAPONS.get(weaponId); }
}
