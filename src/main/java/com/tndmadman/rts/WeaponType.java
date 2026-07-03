package com.tndmadman.rts;

import java.awt.Color;

final class WeaponType {
    final String id, name;
    final double range, damage, cooldownSeconds;
    final boolean beam;
    final Color color;

    WeaponType(String id, String name, double range, double damage, double cooldownSeconds, boolean beam, Color color) {
        this.id = id;
        this.name = name;
        this.range = range;
        this.damage = damage;
        this.cooldownSeconds = cooldownSeconds;
        this.beam = beam;
        this.color = color;
    }
}
