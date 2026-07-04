package com.tndmadman.rts;

import java.awt.Color;

final class WeaponType {
    final String id, name;
    final double range, damage, cooldownSeconds, shotSpeed, tracking;
    final boolean beam, movingShot, screenWeapon, stoppable;
    final Color color;

    WeaponType(String id, String name, double range, double damage, double cooldownSeconds, boolean beam, Color color) {
        this(id, name, range, damage, cooldownSeconds, beam, color, false, false, false, 0, 0.5);
    }

    WeaponType(String id, String name, double range, double damage, double cooldownSeconds, boolean beam, Color color,
               boolean movingShot, boolean screenWeapon, boolean stoppable, double shotSpeed, double tracking) {
        this.id = id;
        this.name = name;
        this.range = range;
        this.damage = damage;
        this.cooldownSeconds = cooldownSeconds;
        this.beam = beam;
        this.color = color;
        this.movingShot = movingShot;
        this.screenWeapon = screenWeapon;
        this.stoppable = stoppable;
        this.shotSpeed = shotSpeed;
        this.tracking = tracking;
    }
}
