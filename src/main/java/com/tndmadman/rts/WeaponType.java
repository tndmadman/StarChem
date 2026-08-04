package com.tndmadman.rts;

import java.awt.Color;
import java.util.List;
import java.util.Set;

final class WeaponType {
    final String id, name;
    final double range, damage, cooldownSeconds, shotSpeed, tracking;
    final boolean beam, movingShot, screenWeapon, stoppable;
    final Color color;
    final Set<String> compatibleHulls;
    final Set<String> requiredResearch;
    final List<Cost> installationCost;

    WeaponType(String id, String name, double range, double damage, double cooldownSeconds, boolean beam, Color color) {
        this(id, name, range, damage, cooldownSeconds, beam, color, false, false, false, 0, 0.5,
                Set.of(), Set.of(), List.of());
    }

    WeaponType(String id, String name, double range, double damage, double cooldownSeconds, boolean beam, Color color,
               boolean movingShot, boolean screenWeapon, boolean stoppable, double shotSpeed, double tracking) {
        this(id, name, range, damage, cooldownSeconds, beam, color, movingShot, screenWeapon, stoppable,
                shotSpeed, tracking, Set.of(), Set.of(), List.of());
    }

    WeaponType(String id, String name, double range, double damage, double cooldownSeconds, boolean beam, Color color,
               boolean movingShot, boolean screenWeapon, boolean stoppable, double shotSpeed, double tracking,
               Set<String> compatibleHulls, Set<String> requiredResearch, List<Cost> installationCost) {
        this.id = id == null ? "" : id.trim();
        this.name = name == null || name.isBlank() ? this.id : name.trim();
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
        this.compatibleHulls = compatibleHulls == null ? Set.of() : Set.copyOf(compatibleHulls);
        this.requiredResearch = requiredResearch == null ? Set.of() : Set.copyOf(requiredResearch);
        this.installationCost = installationCost == null ? List.of() : List.copyOf(installationCost);
    }

    boolean compatibleWith(String hullId) {
        return hullId != null && compatibleHulls.contains(hullId);
    }
}
