package com.tndmadman.rts;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

/** Regression coverage for issue #403 combat and propulsion visual effects. */
public final class Issue403CombatVfxValidator {
    private static final int IMAGE_SIZE = 320;

    private Issue403CombatVfxValidator() { }

    public static void main(String[] args) throws Exception {
        validateWeaponFamilyCoverage();
        validateShipProfiles();
        validateVisualFacingConvention();
        validatePropulsionAndZoomLod();
        validatePropulsionDirection();
        validateMuzzleDirection();
        validateShieldAndHullImpactsStayCosmetic();
        validateTransientPoolIsBoundedAndCleansUp();
        System.out.println("Issue #403 combat VFX validation passed.");
    }

    private static void validateWeaponFamilyCoverage() {
        Set<WeaponVfxStyle> styles = new HashSet<>();
        for (WeaponType weapon : WeaponRules.WEAPONS.values()) styles.add(WeaponVfxStyle.forWeapon(weapon));
        require(styles.size() >= 3, "fewer than three weapon VFX families are represented by the live catalogue");
        require(styles.contains(WeaponVfxStyle.BEAM), "live weapon catalogue has no beam VFX family");
        require(styles.contains(WeaponVfxStyle.POINT_DEFENSE), "live weapon catalogue has no point-defense VFX family");
        require(styles.contains(WeaponVfxStyle.HEAVY_PROJECTILE), "live weapon catalogue has no missile/torpedo projectile VFX family");

        Unit source = new Unit("VFX_SOURCE", 1, Rules.STARTING_SHIP, 54, 160);
        source.heading = 0;
        source.weaponFlashTimer = 0.20;

        RenderSignature pulse = renderDirect(source, weapon("light_railgun"));
        RenderSignature beam = renderDirect(source, weapon("capital_lance"));
        RenderSignature missile = renderProjectile(weapon("light_missile"));
        require(pulse.pixels > 0 && beam.pixels > 0 && missile.pixels > 0,
                "one or more weapon families produced no visible pixels");
        require(pulse.hash != beam.hash && pulse.hash != missile.hash && beam.hash != missile.hash,
                "pulse, beam, and projectile families collapsed to identical rendered output");
    }

    private static void validateShipProfiles() {
        require(!Rules.SHIPS.isEmpty(), "ship catalogue is empty");
        Set<String> layouts = new HashSet<>();
        ShipType smallest = null;
        ShipType largest = null;
        for (ShipType type : Rules.SHIPS.values()) {
            ShipVfxProfile profile = ShipVfxProfile.forType(type);
            require(profile.engineCount() >= 1, "ship " + type.id + " has no authored engine location");
            require(profile.muzzleCount() >= 1, "ship " + type.id + " has no authored weapon hardpoint");
            layouts.add(profile.engineCount() + ":" + profile.muzzleCount() + ":"
                    + Math.round(profile.engineX) + ":" + Math.round(profile.muzzleX) + ":"
                    + Math.round(profile.driveScale * 100));
            if (smallest == null || type.size.scale < smallest.size.scale) smallest = type;
            if (largest == null || type.size.scale > largest.size.scale) largest = type;
        }
        require(layouts.size() >= 3, "ship classes do not expose enough distinct VFX hardpoint layouts");
        require(smallest != null && largest != null, "could not resolve ship size extremes");
        ShipVfxProfile capital = ShipVfxProfile.forType(largest);
        require(capital.engineCount() >= 2, "largest ship class does not scale to multiple engine emitters");
        require(capital.driveScale >= 1.10, "largest ship class does not scale propulsion effects appropriately");
    }

    private static void validateVisualFacingConvention() {
        require(closeAngle(ShipVisualFacing.heading(0.0), Math.PI),
                "authored ship bow is not rotated 180 degrees from movement heading at east");
        require(closeAngle(ShipVisualFacing.heading(Math.PI / 2.0), -Math.PI / 2.0),
                "authored ship bow is not rotated 180 degrees from movement heading at south");
        require(closeAngle(ShipVisualFacing.heading(Math.PI), 0.0),
                "authored ship bow is not rotated 180 degrees from movement heading at west");
        require(closeAngle(ShipVisualFacing.heading(-Math.PI / 2.0), Math.PI / 2.0),
                "authored ship bow is not rotated 180 degrees from movement heading at north");
    }

    private static void validatePropulsionAndZoomLod() {
        Unit unit = new Unit("VFX_PROPULSION", 2, Rules.STARTING_SHIP, 80, 160);
        unit.heading = 0;
        unit.targetX = unit.x;
        unit.targetY = unit.y;
        RenderSignature idle = renderPropulsion(unit, 1.0);
        require(idle.pixels == 0, "idle ship renders propulsion despite having no movement demand");

        unit.targetX = unit.x + 900;
        RenderSignature close = renderPropulsion(unit, 1.0);
        RenderSignature distant = renderPropulsion(unit, 0.10);
        require(close.pixels > 0, "moving ship does not render propulsion");
        require(distant.pixels > 0, "propulsion disappears entirely at strategic zoom");
        require(distant.pixels < close.pixels, "strategic-zoom propulsion does not degrade its render cost/detail");

        unit.targetY = unit.y + 700;
        RenderSignature turning = renderPropulsion(unit, 1.0);
        require(turning.pixels > close.pixels || turning.hash != close.hash,
                "turning ship does not produce maneuvering-thruster feedback");

        unit.targetY = unit.y;
        unit.afterburnerActive = true;
        RenderSignature afterburner = renderPropulsion(unit, 1.0);
        require(afterburner.pixels >= close.pixels,
                "afterburner propulsion is not at least as visually strong as normal thrust");
    }

    private static void validatePropulsionDirection() {
        Unit unit = new Unit("VFX_DIRECTION", 7, Rules.STARTING_SHIP, 160, 160);
        unit.afterburnerActive = true;

        unit.heading = 0.0;
        unit.targetX = unit.x + 900;
        unit.targetY = unit.y;
        PixelBounds east = bounds(renderPropulsionImage(unit, 1.0));
        require(east.pixels > 0 && east.maxX < unit.x,
                "eastbound ship exhaust is not entirely behind the ship (west of center)");

        unit.heading = Math.PI;
        unit.targetX = unit.x - 900;
        unit.targetY = unit.y;
        PixelBounds west = bounds(renderPropulsionImage(unit, 1.0));
        require(west.pixels > 0 && west.minX > unit.x,
                "westbound ship exhaust is not entirely behind the ship (east of center)");

        unit.heading = Math.PI / 2.0;
        unit.targetX = unit.x;
        unit.targetY = unit.y + 900;
        PixelBounds south = bounds(renderPropulsionImage(unit, 1.0));
        require(south.pixels > 0 && south.maxY < unit.y,
                "southbound ship exhaust is not entirely behind the ship (north of center)");

        unit.heading = -Math.PI / 2.0;
        unit.targetX = unit.x;
        unit.targetY = unit.y - 900;
        PixelBounds north = bounds(renderPropulsionImage(unit, 1.0));
        require(north.pixels > 0 && north.minY > unit.y,
                "northbound ship exhaust is not entirely behind the ship (south of center)");
    }

    private static void validateMuzzleDirection() throws Exception {
        Unit source = new Unit("VFX_MUZZLE_DIRECTION", 9, Rules.STARTING_SHIP, 160, 160);
        WeaponType weapon = weapon("light_railgun");

        source.heading = 0.0;
        CombatVfxSystem east = new CombatVfxSystem();
        east.movingWeaponFired(source, weapon, 280, 160);
        double eastX = firstEffectCoordinate(east, "x1");
        require(eastX > source.x, "eastbound ship muzzle is not on the forward/east side of the hull");

        source.heading = Math.PI;
        CombatVfxSystem west = new CombatVfxSystem();
        west.movingWeaponFired(source, weapon, 40, 160);
        double westX = firstEffectCoordinate(west, "x1");
        require(westX < source.x, "westbound ship muzzle is not on the forward/west side of the hull");
    }

    private static void validateShieldAndHullImpactsStayCosmetic() {
        World world = new World("Issue 403 VFX Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        Unit target = new Unit("VFX_TARGET", 1, Rules.STARTING_SHIP, 170, 160);
        world.units.put(target.key(), target);
        String targetKey = CombatTarget.unit(target);
        WeaponType weapon = weapon("light_railgun");

        target.shield = 60;
        target.hp = 100;
        double shieldBefore = target.shield;
        double hpBefore = target.hp;
        ShieldSystem.damage(target, 20);
        double authoritativeShield = target.shield;
        double authoritativeHp = target.hp;
        require(authoritativeShield < shieldBefore && Math.abs(authoritativeHp - hpBefore) < 0.0001,
                "shield-only impact setup did not remain shield-only");
        CombatVfxSystem shieldVfx = new CombatVfxSystem();
        shieldVfx.damageApplied(world, targetKey, 40, 160, weapon, shieldBefore, hpBefore);
        require(Math.abs(target.shield - authoritativeShield) < 0.0001
                        && Math.abs(target.hp - authoritativeHp) < 0.0001,
                "shield VFX mutated authoritative combat state");
        RenderSignature shieldImpact = renderTransient(shieldVfx);

        target.shield = 0;
        target.hp = 100;
        shieldBefore = target.shield;
        hpBefore = target.hp;
        ShieldSystem.damage(target, 20);
        authoritativeShield = target.shield;
        authoritativeHp = target.hp;
        require(Math.abs(authoritativeShield) < 0.0001 && authoritativeHp < hpBefore,
                "hull-impact setup did not damage hull");
        CombatVfxSystem hullVfx = new CombatVfxSystem();
        hullVfx.damageApplied(world, targetKey, 40, 160, weapon, shieldBefore, hpBefore);
        require(Math.abs(target.shield - authoritativeShield) < 0.0001
                        && Math.abs(target.hp - authoritativeHp) < 0.0001,
                "hull VFX mutated authoritative combat state");
        RenderSignature hullImpact = renderTransient(hullVfx);

        require(shieldImpact.pixels > 0 && hullImpact.pixels > 0,
                "shield or hull impact produced no visible feedback");
        require(shieldImpact.hash != hullImpact.hash,
                "shield and hull impacts render identically");
    }

    private static void validateTransientPoolIsBoundedAndCleansUp() throws Exception {
        CombatVfxSystem vfx = new CombatVfxSystem();
        Unit source = new Unit("VFX_POOL", 3, Rules.STARTING_SHIP, 50, 50);
        source.heading = 0;
        WeaponType missile = weapon("light_missile");
        for (int i = 0; i < 5000; i++) {
            vfx.movingWeaponFired(source, missile, 250, 50 + i % 7);
        }

        Field kindField = CombatVfxSystem.class.getDeclaredField("kind");
        kindField.setAccessible(true);
        byte[] slots = (byte[])kindField.get(vfx);
        require(slots.length >= 128 && slots.length <= 1024,
                "transient VFX pool is outside the expected bounded capacity");
        require(activeSlots(slots) <= slots.length,
                "transient VFX active count exceeded fixed pool capacity");

        vfx.update(2.0);
        require(activeSlots(slots) == 0, "expired transient VFX were not reclaimed");
    }

    private static RenderSignature renderDirect(Unit source, WeaponType weapon) {
        BufferedImage image = image();
        Graphics2D g2 = image.createGraphics();
        new CombatVfxSystem().drawDirectFire(g2, source, 270, 160, weapon);
        g2.dispose();
        return signature(image);
    }

    private static RenderSignature renderProjectile(WeaponType weapon) {
        BufferedImage image = image();
        Graphics2D g2 = image.createGraphics();
        ProjectileShot shot = new ProjectileShot(1, "VFX_SOURCE", weapon.id, "", 170, 160);
        shot.lastX = 110;
        shot.lastY = 160;
        shot.x = 170;
        shot.y = 160;
        new CombatVfxSystem().drawProjectile(g2, shot);
        g2.dispose();
        return signature(image);
    }

    private static RenderSignature renderPropulsion(Unit unit, double zoom) {
        return signature(renderPropulsionImage(unit, zoom));
    }

    private static BufferedImage renderPropulsionImage(Unit unit, double zoom) {
        BufferedImage image = image();
        Graphics2D g2 = image.createGraphics();
        CombatVfxSystem.drawPropulsion(g2, unit, zoom);
        g2.dispose();
        return image;
    }

    private static RenderSignature renderTransient(CombatVfxSystem vfx) {
        BufferedImage image = image();
        Graphics2D g2 = image.createGraphics();
        vfx.draw(g2);
        g2.dispose();
        return signature(image);
    }

    private static BufferedImage image() {
        return new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_ARGB);
    }

    private static RenderSignature signature(BufferedImage image) {
        long hash = 0xcbf29ce484222325L;
        int pixels = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                if ((argb >>> 24) != 0) pixels++;
                hash ^= argb & 0xffffffffL;
                hash *= 0x100000001b3L;
            }
        }
        return new RenderSignature(hash, pixels);
    }

    private static PixelBounds bounds(BufferedImage image) {
        int minX = image.getWidth();
        int minY = image.getHeight();
        int maxX = -1;
        int maxY = -1;
        int pixels = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) == 0) continue;
                pixels++;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        return new PixelBounds(minX, minY, maxX, maxY, pixels);
    }

    private static double firstEffectCoordinate(CombatVfxSystem vfx, String fieldName) throws Exception {
        Field field = CombatVfxSystem.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        double[] coordinates = (double[])field.get(vfx);
        return coordinates[0];
    }

    private static boolean closeAngle(double actual, double expected) {
        double delta = actual - expected;
        while (delta > Math.PI) delta -= Math.PI * 2;
        while (delta < -Math.PI) delta += Math.PI * 2;
        return Math.abs(delta) < 0.000001;
    }

    private static int activeSlots(byte[] slots) {
        int active = 0;
        for (byte slot : slots) if (slot != 0) active++;
        return active;
    }

    private static WeaponType weapon(String id) {
        WeaponType weapon = WeaponRules.WEAPONS.get(id);
        if (weapon == null) throw new IllegalStateException("Missing configured weapon: " + id);
        return weapon;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record RenderSignature(long hash, int pixels) { }
    private record PixelBounds(int minX, int minY, int maxX, int maxY, int pixels) { }
}