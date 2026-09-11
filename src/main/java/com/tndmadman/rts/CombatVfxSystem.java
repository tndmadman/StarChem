package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.util.HashMap;
import java.util.Map;

/**
 * Lightweight cosmetic combat effects. Authoritative combat remains entirely in WeaponSystem,
 * CombatTarget and ShieldSystem; this class only observes successful combat events and renders them.
 */
final class CombatVfxSystem {
    private static final int MAX_EFFECTS = 512;
    private static final byte NONE = 0;
    private static final byte MUZZLE = 1;
    private static final byte SHIELD_HIT = 2;
    private static final byte HULL_HIT = 3;
    private static final byte POINT_DEFENSE = 4;
    private static final byte INTERCEPT = 5;

    private static final Stroke HAIRLINE = new BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Stroke THIN = new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Stroke MEDIUM = new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Stroke HEAVY = new BasicStroke(4.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Stroke CAPITAL = new BasicStroke(6.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Stroke GLOW = new BasicStroke(8.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

    private static final int CORE_RGB = 0xEAFBFF;
    private static final int ENGINE_CORE_RGB = 0xC8F7FF;
    private static final int HULL_SPARK_RGB = 0xFFD18A;
    private static final Map<Integer, Color[]> COLOR_CACHE = new HashMap<>();
    private static final int[] COLOR_ALPHA = {45, 85, 135, 195, 240};

    private final byte[] kind = new byte[MAX_EFFECTS];
    private final byte[] style = new byte[MAX_EFFECTS];
    private final double[] x1 = new double[MAX_EFFECTS];
    private final double[] y1 = new double[MAX_EFFECTS];
    private final double[] x2 = new double[MAX_EFFECTS];
    private final double[] y2 = new double[MAX_EFFECTS];
    private final double[] size = new double[MAX_EFFECTS];
    private final double[] life = new double[MAX_EFFECTS];
    private final double[] maxLife = new double[MAX_EFFECTS];
    private final int[] rgb = new int[MAX_EFFECTS];
    private int cursor;

    void update(double dt) {
        if (!Double.isFinite(dt) || dt <= 0) return;
        for (int i = 0; i < MAX_EFFECTS; i++) {
            if (kind[i] == NONE) continue;
            life[i] -= dt;
            if (life[i] <= 0) {
                kind[i] = NONE;
                life[i] = 0;
            }
        }
    }

    void movingWeaponFired(Unit unit, WeaponType weapon, double tx, double ty) {
        if (unit == null || weapon == null) return;
        ShipVfxProfile profile = ShipVfxProfile.forType(unit.type());
        int mount = mountIndex(unit, weapon, profile.muzzleCount());
        double visualHeading = ShipVisualFacing.heading(unit.heading);
        double c = Math.cos(visualHeading);
        double s = Math.sin(visualHeading);
        double localY = profile.muzzleY(mount);
        double mx = unit.x + c * profile.muzzleX - s * localY;
        double my = unit.y + s * profile.muzzleX + c * localY;
        double scale = Math.max(1.0, unit.type().size.scale);
        double radius = WeaponVfxStyle.forWeapon(weapon) == WeaponVfxStyle.HEAVY_PROJECTILE
                ? 7.0 + scale * 2.4 : 4.0 + scale * 1.4;
        spawn(MUZZLE, WeaponVfxStyle.forWeapon(weapon), mx, my, tx, ty, radius, 0.14, weaponRgb(weapon));
    }

    void pointDefense(Unit unit, WeaponType weapon, double tx, double ty) {
        if (unit == null || weapon == null) return;
        ShipVfxProfile profile = ShipVfxProfile.forType(unit.type());
        int mount = mountIndex(unit, weapon, profile.muzzleCount());
        double visualHeading = ShipVisualFacing.heading(unit.heading);
        double c = Math.cos(visualHeading);
        double s = Math.sin(visualHeading);
        double localY = profile.muzzleY(mount);
        double mx = unit.x + c * profile.muzzleX - s * localY;
        double my = unit.y + s * profile.muzzleX + c * localY;
        int color = weaponRgb(weapon);
        spawn(POINT_DEFENSE, WeaponVfxStyle.POINT_DEFENSE, mx, my, tx, ty, 4.0, 0.12, color);
        spawn(INTERCEPT, WeaponVfxStyle.POINT_DEFENSE, tx, ty, 0, 0, 7.0, 0.18, color);
    }

    double targetShield(World world, String key) {
        Unit unit = CombatTarget.unit(world, key);
        if (unit != null) return unit.shield;
        Base base = CombatTarget.base(world, key);
        return base == null ? 0 : base.shield;
    }

    double targetHp(World world, String key) {
        Unit unit = CombatTarget.unit(world, key);
        if (unit != null) return unit.hp;
        Base base = CombatTarget.base(world, key);
        return base == null ? 0 : base.hp;
    }

    void damageApplied(World world, String targetKey, double sourceX, double sourceY, WeaponType weapon,
                       double shieldBefore, double hpBefore) {
        Unit unit = CombatTarget.unit(world, targetKey);
        Base base = CombatTarget.base(world, targetKey);
        if (unit == null && base == null) return;

        double tx = unit != null ? unit.x : base.x;
        double ty = unit != null ? unit.y : base.y;
        double shieldAfter = unit != null ? unit.shield : base.shield;
        double hpAfter = unit != null ? unit.hp : base.hp;
        double radius = unit != null
                ? Math.max(20.0, 27.0 * unit.type().size.scale)
                : Math.max(30.0, base.type().buildRadius * 0.72);
        double angle = Math.atan2(sourceY - ty, sourceX - tx);
        int color = weaponRgb(weapon);

        if (shieldBefore > shieldAfter + 0.001) {
            spawn(SHIELD_HIT, WeaponVfxStyle.forWeapon(weapon), tx, ty, angle, 0,
                    radius, 0.26, color);
        }
        if (hpBefore > hpAfter + 0.001) {
            double ix = tx + Math.cos(angle) * radius * 0.86;
            double iy = ty + Math.sin(angle) * radius * 0.86;
            double impactScale = weapon == null ? 1.0 : Math.max(1.0, Math.min(2.4, weapon.damage / 110.0));
            spawn(HULL_HIT, WeaponVfxStyle.forWeapon(weapon), ix, iy, angle, 0,
                    7.0 * impactScale, 0.30, color);
        }
    }

    void draw(Graphics2D g2) {
        if (g2 == null) return;
        double zoom = SelectionRenderPolicy.scale(g2);
        if (zoom < 0.08) return;
        Stroke oldStroke = g2.getStroke();
        Color oldColor = g2.getColor();
        for (int i = 0; i < MAX_EFFECTS; i++) {
            if (kind[i] == NONE || life[i] <= 0) continue;
            int level = fadeLevel(life[i] / Math.max(0.001, maxLife[i]));
            switch (kind[i]) {
                case MUZZLE -> drawMuzzle(g2, i, level, zoom);
                case SHIELD_HIT -> drawShieldHit(g2, i, level, zoom);
                case HULL_HIT -> drawHullHit(g2, i, level, zoom);
                case POINT_DEFENSE -> drawPointDefense(g2, i, level, zoom);
                case INTERCEPT -> drawIntercept(g2, i, level, zoom);
                default -> { }
            }
        }
        g2.setStroke(oldStroke);
        g2.setColor(oldColor);
    }

    void drawDirectFire(Graphics2D g2, Unit unit, double tx, double ty, WeaponType weapon) {
        if (g2 == null || unit == null || weapon == null || unit.weaponFlashTimer <= 0) return;
        double zoom = SelectionRenderPolicy.scale(g2);
        if (zoom < 0.08) return;
        ShipVfxProfile profile = ShipVfxProfile.forType(unit.type());
        int mount = mountIndex(unit, weapon, profile.muzzleCount());
        double visualHeading = ShipVisualFacing.heading(unit.heading);
        double c = Math.cos(visualHeading);
        double s = Math.sin(visualHeading);
        double localY = profile.muzzleY(mount);
        double mx = unit.x + c * profile.muzzleX - s * localY;
        double my = unit.y + s * profile.muzzleX + c * localY;
        if (!RenderCulling.segmentVisible(g2, mx, my, tx, ty, 28)) return;

        WeaponVfxStyle family = WeaponVfxStyle.forWeapon(weapon);
        int color = weaponRgb(weapon);
        double hullScale = Math.max(1.0, unit.type().size.scale);
        int level = unit.weaponFlashTimer > 0.15 ? 4 : unit.weaponFlashTimer > 0.075 ? 3 : 2;
        Stroke oldStroke = g2.getStroke();
        Color oldColor = g2.getColor();

        if (family == WeaponVfxStyle.BEAM) {
            g2.setStroke(hullScale >= 2.6 ? CAPITAL : HEAVY);
            g2.setColor(cachedColor(color, Math.max(1, level - 2)));
            g2.drawLine(round(mx), round(my), round(tx), round(ty));
            g2.setStroke(hullScale >= 2.6 ? MEDIUM : THIN);
            g2.setColor(cachedColor(CORE_RGB, level));
            g2.drawLine(round(mx), round(my), round(tx), round(ty));
        } else {
            double dx = tx - mx;
            double dy = ty - my;
            g2.setStroke(hullScale >= 2.6 ? HEAVY : MEDIUM);
            g2.setColor(cachedColor(color, level));
            int segments = zoom < 0.45 ? 1 : 3;
            for (int i = 0; i < segments; i++) {
                double start = segments == 1 ? 0.18 : 0.10 + i * 0.29;
                double end = Math.min(1.0, start + (segments == 1 ? 0.58 : 0.15));
                g2.drawLine(round(mx + dx * start), round(my + dy * start),
                        round(mx + dx * end), round(my + dy * end));
            }
        }
        drawMuzzleFlash(g2, mx, my, tx, ty, color, level, 4.2 + hullScale * 1.7, zoom);
        g2.setStroke(oldStroke);
        g2.setColor(oldColor);
    }

    void drawProjectile(Graphics2D g2, ProjectileShot shot) {
        if (g2 == null || shot == null) return;
        WeaponType weapon = shot.weapon();
        if (weapon == null) return;
        double zoom = SelectionRenderPolicy.scale(g2);
        if (zoom < 0.08) return;
        WeaponVfxStyle family = WeaponVfxStyle.forWeapon(weapon);
        int color = weaponRgb(weapon);
        double dx = shot.x - shot.lastX;
        double dy = shot.y - shot.lastY;
        double length = Math.hypot(dx, dy);
        double nx = length > 0.001 ? dx / length : 1.0;
        double ny = length > 0.001 ? dy / length : 0.0;
        double damageScale = Math.max(1.0, Math.min(2.6, weapon.damage / 110.0));
        Stroke oldStroke = g2.getStroke();
        Color oldColor = g2.getColor();

        if (zoom < 0.22) {
            int r = family == WeaponVfxStyle.HEAVY_PROJECTILE ? 6 : 4;
            g2.setColor(cachedColor(color, 4));
            g2.fillOval(round(shot.x) - r, round(shot.y) - r, r * 2, r * 2);
        } else if (family == WeaponVfxStyle.HEAVY_PROJECTILE) {
            double trail = 16.0 + 9.0 * damageScale;
            double bx = shot.x - nx * trail;
            double by = shot.y - ny * trail;
            g2.setStroke(damageScale > 1.8 ? CAPITAL : HEAVY);
            g2.setColor(cachedColor(color, 1));
            g2.drawLine(round(bx), round(by), round(shot.x), round(shot.y));
            g2.setStroke(MEDIUM);
            g2.setColor(cachedColor(color, 4));
            g2.drawLine(round(shot.x - nx * trail * 0.70), round(shot.y - ny * trail * 0.70),
                    round(shot.x), round(shot.y));
            if (weapon.stoppable) {
                g2.setStroke(THIN);
                g2.setColor(cachedColor(ENGINE_CORE_RGB, 3));
                g2.drawLine(round(bx), round(by), round(shot.x - nx * trail * 0.45),
                        round(shot.y - ny * trail * 0.45));
            }
            int r = Math.max(5, Math.min(11, round(4 + damageScale * 2.4)));
            g2.setColor(cachedColor(CORE_RGB, 4));
            g2.fillOval(round(shot.x) - r / 2, round(shot.y) - r / 2, r, r);
        } else {
            double trail = 8.0 + 5.0 * damageScale;
            g2.setStroke(MEDIUM);
            g2.setColor(cachedColor(color, 4));
            g2.drawLine(round(shot.x - nx * trail), round(shot.y - ny * trail),
                    round(shot.x), round(shot.y));
            int r = weapon.damage >= 100 ? 5 : 4;
            g2.fillOval(round(shot.x) - r / 2, round(shot.y) - r / 2, r, r);
        }
        g2.setStroke(oldStroke);
        g2.setColor(oldColor);
    }

    static void drawPropulsion(Graphics2D g2, Unit unit, double zoom) {
        if (g2 == null || unit == null || zoom < 0.08) return;
        double dx = unit.targetX - unit.x;
        double dy = unit.targetY - unit.y;
        double targetDistance = Math.hypot(dx, dy);
        if (targetDistance <= 2.0 && !unit.afterburnerActive) return;

        ShipVfxProfile profile = ShipVfxProfile.forType(unit.type());
        double hullScale = Math.max(0.65, unit.type().size.scale);
        double power = unit.afterburnerActive ? 1.48
                : clamp(0.72 + unit.type().speed / 520.0, 0.82, 1.24);
        double trail = (10.0 + 8.0 * hullScale) * profile.driveScale * power;
        if (zoom < 0.24) trail *= 0.60;
        double visualHeading = ShipVisualFacing.heading(unit.heading);
        double c = Math.cos(visualHeading);
        double s = Math.sin(visualHeading);
        Stroke oldStroke = g2.getStroke();
        Color oldColor = g2.getColor();
        Stroke outer = hullScale >= 2.6 ? CAPITAL : hullScale >= 1.5 ? HEAVY : MEDIUM;
        Stroke inner = hullScale >= 2.6 ? HEAVY : THIN;

        for (int i = 0; i < profile.engineCount(); i++) {
            double localY = profile.engineY(i);
            double ex = unit.x + c * profile.engineX - s * localY;
            double ey = unit.y + s * profile.engineX + c * localY;
            double tailX = ex + c * trail;
            double tailY = ey + s * trail;
            g2.setStroke(outer);
            g2.setColor(cachedColor(profile.exhaustRgb, 1));
            g2.drawLine(round(ex), round(ey), round(tailX), round(tailY));
            if (zoom >= 0.20) {
                g2.setStroke(inner);
                g2.setColor(cachedColor(ENGINE_CORE_RGB, unit.afterburnerActive ? 4 : 3));
                g2.drawLine(round(ex), round(ey), round(ex + c * trail * 0.62), round(ey + s * trail * 0.62));
            }
            int glow = hullScale >= 2.6 ? 8 : hullScale >= 1.5 ? 6 : 4;
            g2.setColor(cachedColor(ENGINE_CORE_RGB, 4));
            g2.fillOval(round(ex) - glow / 2, round(ey) - glow / 2, glow, glow);
        }

        if (zoom >= 0.55 && targetDistance > 4.0) {
            double desired = Math.atan2(dy, dx);
            double turn = angleDelta(unit.heading, desired);
            if (Math.abs(turn) > 0.10) {
                double side = turn > 0 ? -1.0 : 1.0;
                double localY = side * profile.thrusterY;
                double px = unit.x - s * localY;
                double py = unit.y + c * localY;
                double ox = -s * side;
                double oy = c * side;
                double puff = 5.0 + 3.0 * hullScale;
                g2.setStroke(THIN);
                g2.setColor(cachedColor(profile.exhaustRgb, 3));
                g2.drawLine(round(px), round(py), round(px + ox * puff), round(py + oy * puff));
            }
        }
        g2.setStroke(oldStroke);
        g2.setColor(oldColor);
    }

    private void drawMuzzle(Graphics2D g2, int i, int level, double zoom) {
        drawMuzzleFlash(g2, x1[i], y1[i], x2[i], y2[i], rgb[i], level, size[i], zoom);
    }

    private void drawShieldHit(Graphics2D g2, int i, int level, double zoom) {
        double radius = size[i];
        double angle = x2[i];
        double ix = x1[i] + Math.cos(angle) * radius;
        double iy = y1[i] + Math.sin(angle) * radius;
        g2.setColor(cachedColor(rgb[i], Math.max(2, level)));
        int dot = zoom < 0.30 ? 5 : 8;
        g2.fillOval(round(ix) - dot / 2, round(iy) - dot / 2, dot, dot);
        if (zoom < 0.30) return;
        g2.setStroke(radius >= 70 ? HEAVY : MEDIUM);
        int r = round(radius);
        int start = round(-Math.toDegrees(angle) - 52.0);
        g2.drawArc(round(x1[i]) - r, round(y1[i]) - r, r * 2, r * 2, start, 104);
        if (zoom >= 0.75) {
            g2.setStroke(HAIRLINE);
            g2.setColor(cachedColor(CORE_RGB, Math.max(1, level - 1)));
            int inner = Math.max(2, r - 4);
            g2.drawArc(round(x1[i]) - inner, round(y1[i]) - inner,
                    inner * 2, inner * 2, start + 8, 88);
        }
    }

    private void drawHullHit(Graphics2D g2, int i, int level, double zoom) {
        int r = Math.max(3, round(size[i] * (0.58 + 0.42 * life[i] / maxLife[i])));
        g2.setColor(cachedColor(HULL_SPARK_RGB, level));
        g2.fillOval(round(x1[i]) - r / 2, round(y1[i]) - r / 2, r, r);
        if (zoom < 0.35) return;
        g2.setStroke(THIN);
        g2.setColor(cachedColor(rgb[i], Math.max(2, level - 1)));
        int sparks = zoom < 0.70 ? 2 : 4;
        double base = x2[i];
        for (int n = 0; n < sparks; n++) {
            double a = base + (n - (sparks - 1) / 2.0) * 0.62 + (i % 3) * 0.17;
            double len = size[i] * (1.2 + n * 0.18);
            g2.drawLine(round(x1[i]), round(y1[i]),
                    round(x1[i] + Math.cos(a) * len), round(y1[i] + Math.sin(a) * len));
        }
    }

    private void drawPointDefense(Graphics2D g2, int i, int level, double zoom) {
        g2.setStroke(zoom < 0.35 ? HAIRLINE : THIN);
        g2.setColor(cachedColor(rgb[i], level));
        g2.drawLine(round(x1[i]), round(y1[i]), round(x2[i]), round(y2[i]));
    }

    private void drawIntercept(Graphics2D g2, int i, int level, double zoom) {
        int r = zoom < 0.35 ? 4 : round(size[i]);
        g2.setColor(cachedColor(rgb[i], level));
        g2.fillOval(round(x1[i]) - r / 2, round(y1[i]) - r / 2, r, r);
        if (zoom >= 0.55) {
            g2.setStroke(HAIRLINE);
            g2.setColor(cachedColor(CORE_RGB, Math.max(1, level - 1)));
            g2.drawLine(round(x1[i] - r), round(y1[i]), round(x1[i] + r), round(y1[i]));
            g2.drawLine(round(x1[i]), round(y1[i] - r), round(x1[i]), round(y1[i] + r));
        }
    }

    private static void drawMuzzleFlash(Graphics2D g2, double mx, double my, double tx, double ty,
                                        int rgb, int level, double radius, double zoom) {
        double dx = tx - mx;
        double dy = ty - my;
        double d = Math.hypot(dx, dy);
        double nx = d > 0.001 ? dx / d : 1.0;
        double ny = d > 0.001 ? dy / d : 0.0;
        int r = Math.max(3, round(radius * (zoom < 0.28 ? 0.72 : 1.0)));
        g2.setColor(cachedColor(rgb, Math.max(2, level - 1)));
        g2.fillOval(round(mx) - r, round(my) - r, r * 2, r * 2);
        g2.setColor(cachedColor(CORE_RGB, level));
        int core = Math.max(2, r / 2);
        g2.fillOval(round(mx) - core, round(my) - core, core * 2, core * 2);
        if (zoom >= 0.35) {
            g2.setStroke(MEDIUM);
            g2.drawLine(round(mx), round(my), round(mx + nx * radius * 2.4), round(my + ny * radius * 2.4));
        }
    }

    private void spawn(byte effectKind, WeaponVfxStyle family, double ax, double ay, double bx, double by,
                       double effectSize, double duration, int color) {
        int slot = cursor;
        cursor = (cursor + 1) % MAX_EFFECTS;
        kind[slot] = effectKind;
        style[slot] = (byte)(family == null ? 0 : family.ordinal());
        x1[slot] = ax;
        y1[slot] = ay;
        x2[slot] = bx;
        y2[slot] = by;
        size[slot] = Math.max(1.0, effectSize);
        life[slot] = Math.max(0.01, duration);
        maxLife[slot] = life[slot];
        rgb[slot] = color & 0xFFFFFF;
    }

    private static int mountIndex(Unit unit, WeaponType weapon, int count) {
        if (count <= 1) return 0;
        int hash = unit.unitId * 31 + (weapon.id == null ? 0 : weapon.id.hashCode());
        return Math.floorMod(hash, count);
    }

    private static int weaponRgb(WeaponType weapon) {
        Color color = weapon == null ? null : weapon.color;
        return color == null ? 0x9FDCFF : color.getRGB() & 0xFFFFFF;
    }

    private static int fadeLevel(double fraction) {
        if (fraction > 0.72) return 4;
        if (fraction > 0.46) return 3;
        if (fraction > 0.22) return 2;
        return 1;
    }

    private static Color cachedColor(int rgb, int level) {
        int key = rgb & 0xFFFFFF;
        Color[] colors = COLOR_CACHE.get(key);
        if (colors == null) {
            colors = new Color[COLOR_ALPHA.length];
            int red = key >> 16 & 0xFF;
            int green = key >> 8 & 0xFF;
            int blue = key & 0xFF;
            for (int i = 0; i < colors.length; i++) colors[i] = new Color(red, green, blue, COLOR_ALPHA[i]);
            COLOR_CACHE.put(key, colors);
        }
        return colors[Math.max(0, Math.min(colors.length - 1, level))];
    }

    private static double angleDelta(double from, double to) {
        double delta = to - from;
        while (delta > Math.PI) delta -= Math.PI * 2;
        while (delta < -Math.PI) delta += Math.PI * 2;
        return delta;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int round(double value) { return (int)Math.round(value); }
}