package com.tndmadman.rts;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** Seed-authored, deterministic vector artwork used by the fitting studio. */
final class FittingItemVisuals {
    private static final Map<String,WeaponVisual> WEAPONS = loadWeaponVisuals();

    private FittingItemVisuals() { }

    static Icon weaponIcon(WeaponType weapon, int size) {
        return weapon == null ? emptyIcon(size) : new WeaponIcon(weapon, Math.max(24, size));
    }

    static Icon hullIcon(ShipType ship, int size) {
        return ship == null ? emptyIcon(size) : new HullIcon(ship, Math.max(28, size));
    }

    static Icon emptyIcon(int size) {
        return new EmptyIcon(Math.max(24, size));
    }

    static int weaponSeed(String id) {
        WeaponVisual visual = WEAPONS.get(id == null ? "" : id);
        return visual == null ? stableSeed("weapon:" + id) : visual.seed;
    }

    private static Map<String,WeaponVisual> loadWeaponVisuals() {
        Map<String,WeaponVisual> out = new LinkedHashMap<>();
        Path path = Path.of("config/item-visuals.json");
        if (!Files.isRegularFile(path)) return out;
        try {
            Object parsed = MiniJson.parse(Files.readString(path));
            Map<String,Object> root = ServerSaveStore.object(parsed);
            Map<String,Object> rows = ServerSaveStore.object(root.get("weaponVisuals"));
            for (Map.Entry<String,Object> entry : rows.entrySet()) {
                Map<String,Object> row = ServerSaveStore.object(entry.getValue());
                int seed = number(row.get("seed"), stableSeed("weapon:" + entry.getKey()));
                String styleName = String.valueOf(row.getOrDefault("style", "CANNON"));
                WeaponStyle style;
                try { style = WeaponStyle.valueOf(styleName.trim().toUpperCase(Locale.ROOT)); }
                catch (RuntimeException ignored) { style = WeaponStyle.CANNON; }
                Color color = color(String.valueOf(row.getOrDefault("color", "#9FDCFF")), new Color(0x9FDCFF));
                out.put(entry.getKey(), new WeaponVisual(seed, style, color));
            }
        } catch (Exception ex) {
            System.err.println("Could not load fitting item visuals: " + ex.getMessage());
        }
        return Map.copyOf(out);
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static Color color(String value, Color fallback) {
        try { return Color.decode(value); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static WeaponVisual visual(WeaponType weapon) {
        WeaponVisual authored = WEAPONS.get(weapon.id);
        if (authored != null) return authored;
        WeaponStyle style = weapon.screenWeapon ? WeaponStyle.BEAM_ARRAY
                : weapon.movingShot ? (weapon.damage >= 100 ? WeaponStyle.TORPEDO : WeaponStyle.MISSILE)
                : weapon.beam ? WeaponStyle.LANCE
                : weapon.damage >= 45 ? WeaponStyle.CANNON : WeaponStyle.RAILGUN;
        return new WeaponVisual(stableSeed("weapon:" + weapon.id), style, weapon.color);
    }

    private static int stableSeed(String value) {
        int hash = value == null ? 0 : value.hashCode();
        hash ^= hash << 13;
        hash ^= hash >>> 17;
        hash ^= hash << 5;
        return hash == 0 ? 0x51A7C0DE : hash;
    }

    private enum WeaponStyle { BEAM_ARRAY, RAILGUN, CANNON, FIGHTER, LANCE, MISSILE, TORPEDO }
    private record WeaponVisual(int seed, WeaponStyle style, Color color) { }

    private static final class WeaponIcon implements Icon {
        private final WeaponType weapon;
        private final int size;

        private WeaponIcon(WeaponType weapon, int size) {
            this.weapon = weapon;
            this.size = size;
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            WeaponVisual visual = visual(weapon);
            Graphics2D g = prepared(graphics);
            drawFrame(g, x, y, size, visual.color, visual.seed);
            int pad = Math.max(4, size / 9);
            int gx = x + pad;
            int gy = y + pad;
            int gs = size - pad * 2;
            Random random = new Random(visual.seed);
            switch (visual.style) {
                case BEAM_ARRAY -> drawBeamArray(g, gx, gy, gs, visual.color, random);
                case RAILGUN -> drawRailgun(g, gx, gy, gs, visual.color, random);
                case CANNON -> drawCannon(g, gx, gy, gs, visual.color, random);
                case FIGHTER -> drawFighter(g, gx, gy, gs, visual.color, random);
                case LANCE -> drawLance(g, gx, gy, gs, visual.color, random);
                case MISSILE -> drawMissile(g, gx, gy, gs, visual.color, random, false);
                case TORPEDO -> drawMissile(g, gx, gy, gs, visual.color, random, true);
            }
            g.dispose();
        }
    }

    private static final class HullIcon implements Icon {
        private final ShipType ship;
        private final int size;

        private HullIcon(ShipType ship, int size) {
            this.ship = ship;
            this.size = size;
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            int seed = stableSeed("hull:" + ship.id);
            Random random = new Random(seed);
            Color accent = Color.getHSBColor((seed & 0xFFFF) / 65535f, 0.48f, 0.95f);
            Graphics2D g = prepared(graphics);
            drawFrame(g, x, y, size, accent, seed);
            int cx = x + size / 2;
            int top = y + size / 7;
            int bottom = y + size * 6 / 7;
            int half = Math.max(6, size / 5 + random.nextInt(Math.max(1, size / 10)));
            Polygon hull = new Polygon();
            hull.addPoint(cx, top);
            hull.addPoint(cx + half, y + size * 2 / 5);
            hull.addPoint(cx + half * 3 / 4, bottom);
            hull.addPoint(cx, y + size * 3 / 4);
            hull.addPoint(cx - half * 3 / 4, bottom);
            hull.addPoint(cx - half, y + size * 2 / 5);
            g.setPaint(new GradientPaint(cx - half, top, brighten(accent, 1.25), cx + half, bottom, new Color(12, 22, 32)));
            g.fillPolygon(hull);
            g.setColor(withAlpha(Color.WHITE, 170));
            g.drawPolygon(hull);
            g.setColor(withAlpha(accent, 210));
            g.setStroke(new BasicStroke(Math.max(1.3f, size / 24f)));
            g.drawLine(cx, top + 4, cx, bottom - 4);
            for (int i = 0; i < 2; i++) {
                int yy = y + size * (4 + i) / 7;
                int wing = half + size / 8 + random.nextInt(Math.max(1, size / 8));
                g.drawLine(cx - 3, yy, cx - wing, yy + size / 10);
                g.drawLine(cx + 3, yy, cx + wing, yy + size / 10);
            }
            int engine = Math.max(3, size / 10);
            g.setColor(withAlpha(brighten(accent, 1.45), 230));
            g.fillOval(cx - engine / 2, bottom - engine / 2, engine, engine);
            g.dispose();
        }
    }

    private static final class EmptyIcon implements Icon {
        private final int size;
        private EmptyIcon(int size) { this.size = size; }
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = prepared(graphics);
            g.setPaint(new GradientPaint(x, y, new Color(22, 37, 52), x + size, y + size, new Color(5, 11, 18)));
            g.fill(new RoundRectangle2D.Double(x, y, size - 1, size - 1, size / 4.0, size / 4.0));
            g.setColor(new Color(69, 100, 123));
            g.draw(new RoundRectangle2D.Double(x + .5, y + .5, size - 2, size - 2, size / 4.0, size / 4.0));
            int d = size / 2;
            g.drawOval(x + (size - d) / 2, y + (size - d) / 2, d, d);
            g.drawLine(x + size / 3, y + size / 2, x + size * 2 / 3, y + size / 2);
            g.dispose();
        }
    }

    private static void drawFrame(Graphics2D g, int x, int y, int size, Color color, int seed) {
        Color bright = brighten(color, 1.28);
        g.setPaint(new GradientPaint(x, y, withAlpha(bright, 175), x + size, y + size, new Color(4, 10, 17)));
        g.fill(new RoundRectangle2D.Double(x, y, size - 1, size - 1, size / 4.0, size / 4.0));
        g.setColor(withAlpha(bright, 225));
        g.setStroke(new BasicStroke(Math.max(1.2f, size / 25f)));
        g.draw(new RoundRectangle2D.Double(x + .5, y + .5, size - 2, size - 2, size / 4.0, size / 4.0));
        Random random = new Random(seed ^ 0x6C8E9CF5);
        g.setColor(withAlpha(color, 65));
        for (int i = 0; i < 4; i++) {
            int yy = y + 3 + random.nextInt(Math.max(1, size - 6));
            g.drawLine(x + 3, yy, x + size - 4, yy);
        }
    }

    private static void drawBeamArray(Graphics2D g, int x, int y, int size, Color color, Random random) {
        int beams = 3 + random.nextInt(3);
        g.setStroke(new BasicStroke(Math.max(1.4f, size / 18f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < beams; i++) {
            int yy = y + size * (i + 1) / (beams + 1);
            g.setColor(withAlpha(brighten(color, 1.35), 220 - i * 18));
            g.drawLine(x + size / 6, yy, x + size * 5 / 6, yy - size / 8 + random.nextInt(Math.max(1, size / 4)));
        }
        g.setColor(withAlpha(Color.WHITE, 210));
        g.fillOval(x + size / 3, y + size / 3, size / 3, size / 3);
    }

    private static void drawRailgun(Graphics2D g, int x, int y, int size, Color color, Random random) {
        int mid = y + size / 2;
        int gap = Math.max(3, size / 9);
        g.setStroke(new BasicStroke(Math.max(2f, size / 13f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(withAlpha(darken(color, .45), 230));
        g.drawLine(x + size / 8, mid - gap, x + size * 7 / 8, mid - gap);
        g.drawLine(x + size / 8, mid + gap, x + size * 7 / 8, mid + gap);
        g.setStroke(new BasicStroke(Math.max(1f, size / 28f)));
        g.setColor(withAlpha(brighten(color, 1.45), 230));
        g.drawLine(x + size / 7, mid, x + size * 6 / 7, mid);
        int coil = Math.max(4, size / 7);
        g.draw(new Arc2D.Double(x + size / 3.0, mid - coil, coil * 2.0, coil * 2.0,
                random.nextInt(80), 250, Arc2D.OPEN));
    }

    private static void drawCannon(Graphics2D g, int x, int y, int size, Color color, Random random) {
        int barrel = Math.max(5, size / 5);
        int mid = y + size / 2;
        g.setPaint(new GradientPaint(x, mid, darken(color, .38), x + size, mid, brighten(color, 1.18)));
        g.fillRoundRect(x + size / 7, mid - barrel / 2, size * 5 / 7, barrel, barrel / 2, barrel / 2);
        int chamber = Math.max(8, size / 3);
        g.fillOval(x + size / 5, mid - chamber / 2, chamber, chamber);
        g.setColor(withAlpha(Color.WHITE, 160));
        g.drawOval(x + size / 5, mid - chamber / 2, chamber, chamber);
        g.setColor(withAlpha(brighten(color, 1.4), 230));
        int flash = 4 + random.nextInt(Math.max(2, size / 7));
        g.fillOval(x + size * 5 / 6, mid - flash / 2, flash, flash);
    }

    private static void drawFighter(Graphics2D g, int x, int y, int size, Color color, Random random) {
        int cx = x + size / 2;
        Polygon fighter = new Polygon();
        fighter.addPoint(cx, y + size / 10);
        fighter.addPoint(x + size * 4 / 5, y + size * 4 / 5);
        fighter.addPoint(cx, y + size * 3 / 5);
        fighter.addPoint(x + size / 5, y + size * 4 / 5);
        g.setPaint(new GradientPaint(x, y, brighten(color, 1.3), x + size, y + size, darken(color, .35)));
        g.fillPolygon(fighter);
        g.setColor(withAlpha(Color.WHITE, 185));
        g.drawPolygon(fighter);
        g.setColor(withAlpha(brighten(color, 1.5), 230));
        int flame = Math.max(4, size / 8);
        g.fillOval(cx - flame / 2, y + size * 3 / 4, flame, flame);
    }

    private static void drawLance(Graphics2D g, int x, int y, int size, Color color, Random random) {
        int cx = x + size / 2;
        int core = Math.max(6, size / 5);
        g.setColor(withAlpha(darken(color, .35), 220));
        g.fillOval(cx - core, y + size / 3 - core, core * 2, core * 2);
        g.setColor(withAlpha(brighten(color, 1.5), 230));
        g.fillOval(cx - core / 2, y + size / 3 - core / 2, core, core);
        Path2D beam = new Path2D.Double();
        beam.moveTo(cx - core / 3.0, y + size / 3.0);
        beam.lineTo(cx - size / 9.0, y + size * 9 / 10.0);
        beam.lineTo(cx + size / 9.0, y + size * 9 / 10.0);
        beam.lineTo(cx + core / 3.0, y + size / 3.0);
        beam.closePath();
        g.setPaint(new GradientPaint(cx, y + size / 3, Color.WHITE, cx, y + size, withAlpha(color, 30)));
        g.fill(beam);
        g.setColor(withAlpha(color, 190));
        g.drawOval(x + size / 8, y + size / 8, size * 3 / 4, size * 3 / 4);
    }

    private static void drawMissile(Graphics2D g, int x, int y, int size, Color color, Random random, boolean heavy) {
        int cx = x + size / 2;
        int width = Math.max(6, size / (heavy ? 3 : 4));
        int nose = y + size / 10;
        int tail = y + size * 3 / 4;
        Polygon body = new Polygon();
        body.addPoint(cx, nose);
        body.addPoint(cx + width / 2, y + size / 3);
        body.addPoint(cx + width / 2, tail);
        body.addPoint(cx - width / 2, tail);
        body.addPoint(cx - width / 2, y + size / 3);
        g.setPaint(new GradientPaint(cx - width, nose, brighten(color, 1.3), cx + width, tail, darken(color, .35)));
        g.fillPolygon(body);
        g.setColor(withAlpha(Color.WHITE, 170));
        g.drawPolygon(body);
        g.drawLine(cx - width / 2, tail - size / 8, cx - width, tail + size / 12);
        g.drawLine(cx + width / 2, tail - size / 8, cx + width, tail + size / 12);
        int flame = Math.max(4, size / 8 + random.nextInt(Math.max(1, size / 10)));
        g.setColor(withAlpha(brighten(color, 1.5), 230));
        g.fillOval(cx - flame / 2, tail - 1, flame, size - (tail - y));
    }

    private static Graphics2D prepared(Graphics graphics) {
        Graphics2D g = (Graphics2D)graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), clamp(alpha));
    }

    private static Color brighten(Color color, double factor) {
        return new Color(clamp((int)Math.round(color.getRed() * factor)),
                clamp((int)Math.round(color.getGreen() * factor)),
                clamp((int)Math.round(color.getBlue() * factor)));
    }

    private static Color darken(Color color, double factor) {
        return new Color(clamp((int)Math.round(color.getRed() * factor)),
                clamp((int)Math.round(color.getGreen() * factor)),
                clamp((int)Math.round(color.getBlue() * factor)));
    }

    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }
}
