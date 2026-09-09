package com.tndmadman.rts;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Deterministic, renderer-only system backdrop. Geometry is generated once per system visual
 * and then cheaply repainted; no simulation state or network payload is involved.
 */
final class SpaceBackgroundRenderer {
    private static final int MAX_SCENES = 18;
    private static final Map<String, Scene> SCENES = new LinkedHashMap<>(24, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Scene> eldest) {
            return size() > MAX_SCENES;
        }
    };

    private SpaceBackgroundRenderer() { }

    static void draw(Graphics2D g2, StarSystemDefinition definition, double visualTime) {
        if (g2 == null || definition == null) return;
        Scene scene = scene(definition);
        Graphics2D g = (Graphics2D) g2.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        BufferedImage authored = ArtAssetManager.image(VisualCatalog.system(definition.id()).backgroundAssetPath());
        if (authored != null) {
            g.drawImage(authored, 0, 0, definition.width(), definition.height(), null);
        } else {
            drawBase(g, definition, scene.theme);
            drawNebula(g, scene);
            drawStars(g, definition, scene);
        }
        drawAmbient(g, definition, scene, visualTime);
        g.dispose();
    }

    static int cachedSceneCount() {
        synchronized (SCENES) { return SCENES.size(); }
    }

    private static Scene scene(StarSystemDefinition definition) {
        String key = definition.id() + ":" + definition.width() + "x" + definition.height();
        synchronized (SCENES) {
            return SCENES.computeIfAbsent(key, ignored -> generate(definition));
        }
    }

    private static Scene generate(StarSystemDefinition definition) {
        SystemVisualDefinition visual = VisualCatalog.system(definition.id());
        Theme theme = Theme.of(definition);
        Random random = new Random(visual.visualSeed() ^ ((long) definition.width() << 32) ^ definition.height());

        List<Star> stars = new ArrayList<>();
        int starCount = 420 + Math.min(380, (definition.width() + definition.height()) / 90);
        for (int i = 0; i < starCount; i++) {
            double depth = 0.18 + random.nextDouble() * 0.82;
            double radius = depth < 0.45 ? 0.7 + random.nextDouble() * 1.1 : 1.0 + random.nextDouble() * 2.1;
            int alpha = 50 + (int) Math.round(depth * 145) + random.nextInt(35);
            stars.add(new Star(random.nextDouble() * definition.width(), random.nextDouble() * definition.height(),
                    radius, Math.min(235, alpha), depth, random.nextDouble() < 0.055));
        }

        List<Cloud> clouds = new ArrayList<>();
        int cloudCount = theme.nebula ? 16 : theme.haze ? 8 : 3;
        for (int i = 0; i < cloudCount; i++) {
            double radius = Math.min(definition.width(), definition.height()) * (0.055 + random.nextDouble() * 0.15);
            clouds.add(new Cloud(random.nextDouble() * definition.width(), random.nextDouble() * definition.height(),
                    radius, 0.55 + random.nextDouble() * 1.0, 10 + random.nextInt(theme.nebula ? 25 : 10),
                    random.nextBoolean()));
        }

        List<Drift> drift = new ArrayList<>();
        int driftCount = theme == Theme.ICE ? 105 : theme == Theme.GRAVEYARD ? 74 : theme.nebula ? 92 : 46;
        for (int i = 0; i < driftCount; i++) {
            drift.add(new Drift(random.nextDouble() * definition.width(), random.nextDouble() * definition.height(),
                    1.0 + random.nextDouble() * (theme == Theme.GRAVEYARD ? 5.5 : 2.6),
                    -2.4 + random.nextDouble() * 4.8, -1.8 + random.nextDouble() * 3.6,
                    18 + random.nextInt(34), random.nextDouble() * Math.PI * 2));
        }
        return new Scene(theme, List.copyOf(stars), List.copyOf(clouds), List.copyOf(drift));
    }

    private static void drawBase(Graphics2D g, StarSystemDefinition definition, Theme theme) {
        g.setPaint(new GradientPaint(0, 0, theme.top, definition.width(), definition.height(), theme.bottom));
        g.fillRect(0, 0, definition.width(), definition.height());
    }

    private static void drawNebula(Graphics2D g, Scene scene) {
        if (scene.clouds.isEmpty()) return;
        for (Cloud cloud : scene.clouds) {
            Color source = cloud.secondary ? scene.theme.cloudSecondary : scene.theme.cloudPrimary;
            int alpha = Math.min(70, cloud.alpha);
            g.setColor(new Color(source.getRed(), source.getGreen(), source.getBlue(), alpha));
            double w = cloud.radius * 2.0 * cloud.aspect;
            double h = cloud.radius * 2.0 / Math.sqrt(cloud.aspect);
            g.fill(new Ellipse2D.Double(cloud.x - w * 0.5, cloud.y - h * 0.5, w, h));
            g.setColor(new Color(source.getRed(), source.getGreen(), source.getBlue(), Math.max(4, alpha / 3)));
            g.fill(new Ellipse2D.Double(cloud.x - w * 0.8, cloud.y - h * 0.8, w * 1.6, h * 1.6));
        }
    }

    private static void drawStars(Graphics2D g, StarSystemDefinition definition, Scene scene) {
        Rectangle clip = g.getClipBounds();
        double centerX = clip == null ? definition.width() * 0.5 : clip.getCenterX();
        double centerY = clip == null ? definition.height() * 0.5 : clip.getCenterY();
        for (Star star : scene.stars) {
            double factor = (1.0 - star.depth) * 0.032;
            double x = star.x + (centerX - definition.width() * 0.5) * factor;
            double y = star.y + (centerY - definition.height() * 0.5) * factor;
            double margin = star.radius * 3 + 4;
            if (clip != null && !clip.intersects(x - margin, y - margin, margin * 2, margin * 2)) continue;
            Color tint = scene.theme.starTint;
            g.setColor(new Color(tint.getRed(), tint.getGreen(), tint.getBlue(), star.alpha));
            double d = star.radius * 2;
            g.fill(new Ellipse2D.Double(x - star.radius, y - star.radius, d, d));
            if (star.bright) {
                g.setColor(new Color(tint.getRed(), tint.getGreen(), tint.getBlue(), Math.min(120, star.alpha / 2)));
                g.drawLine((int) Math.round(x - star.radius * 3), (int) Math.round(y),
                        (int) Math.round(x + star.radius * 3), (int) Math.round(y));
                g.drawLine((int) Math.round(x), (int) Math.round(y - star.radius * 3),
                        (int) Math.round(x), (int) Math.round(y + star.radius * 3));
            }
        }
    }

    private static void drawAmbient(Graphics2D g, StarSystemDefinition definition, Scene scene, double time) {
        Rectangle clip = g.getClipBounds();
        for (int i = 0; i < scene.drift.size(); i++) {
            Drift particle = scene.drift.get(i);
            double x = wrap(particle.x + particle.vx * time, definition.width());
            double y = wrap(particle.y + particle.vy * time, definition.height());
            double margin = particle.size * 4;
            if (clip != null && !clip.intersects(x - margin, y - margin, margin * 2, margin * 2)) continue;
            Color c = scene.theme.ambient;
            int alpha = particle.alpha;
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha));

            if (scene.theme == Theme.ICE) {
                int r = Math.max(1, (int) Math.round(particle.size));
                int[] xs = {(int)x, (int)(x + Math.cos(particle.angle) * r * 2), (int)(x - Math.sin(particle.angle) * r)};
                int[] ys = {(int)(y - r), (int)(y + r), (int)(y + Math.cos(particle.angle) * r)};
                g.fillPolygon(xs, ys, 3);
            } else if (scene.theme == Theme.GRAVEYARD && i % 5 == 0) {
                double a = particle.angle;
                double len = particle.size * 4.5;
                g.drawLine((int)(x - Math.cos(a) * len), (int)(y - Math.sin(a) * len),
                        (int)(x + Math.cos(a) * len), (int)(y + Math.sin(a) * len));
                g.drawLine((int)x, (int)y,
                        (int)(x + Math.cos(a + 1.35) * len * 0.45),
                        (int)(y + Math.sin(a + 1.35) * len * 0.45));
            } else if (scene.theme.nebula) {
                double w = particle.size * 8.0;
                double h = particle.size * 2.2;
                g.fill(new Ellipse2D.Double(x - w * 0.5, y - h * 0.5, w, h));
            } else {
                double r = Math.max(0.7, particle.size * 0.42);
                g.fill(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
            }
        }
    }

    private static double wrap(double value, double extent) {
        if (!(extent > 0)) return value;
        double result = value % extent;
        return result < 0 ? result + extent : result;
    }

    private record Scene(Theme theme, List<Star> stars, List<Cloud> clouds, List<Drift> drift) { }
    private record Star(double x, double y, double radius, int alpha, double depth, boolean bright) { }
    private record Cloud(double x, double y, double radius, double aspect, int alpha, boolean secondary) { }
    private record Drift(double x, double y, double size, double vx, double vy, int alpha, double angle) { }

    private enum Theme {
        DEEP(new Color(5, 9, 18), new Color(11, 18, 31), new Color(64, 90, 124), new Color(42, 70, 105),
                new Color(205, 222, 240), new Color(115, 142, 170), false, false),
        NEBULA(new Color(7, 8, 24), new Color(22, 12, 45), new Color(80, 46, 142), new Color(42, 91, 154),
                new Color(215, 215, 255), new Color(135, 112, 190), true, true),
        ICE(new Color(4, 12, 22), new Color(9, 25, 38), new Color(42, 86, 112), new Color(82, 121, 148),
                new Color(210, 238, 255), new Color(145, 205, 225), false, true),
        FORGE(new Color(15, 7, 8), new Color(30, 14, 12), new Color(115, 45, 28), new Color(89, 58, 35),
                new Color(255, 224, 190), new Color(208, 111, 70), false, true),
        GRAVEYARD(new Color(9, 10, 12), new Color(20, 18, 18), new Color(73, 58, 52), new Color(47, 54, 59),
                new Color(210, 214, 218), new Color(132, 115, 103), false, true);

        final Color top, bottom, cloudPrimary, cloudSecondary, starTint, ambient;
        final boolean nebula, haze;

        Theme(Color top, Color bottom, Color cloudPrimary, Color cloudSecondary,
              Color starTint, Color ambient, boolean nebula, boolean haze) {
            this.top = top; this.bottom = bottom; this.cloudPrimary = cloudPrimary; this.cloudSecondary = cloudSecondary;
            this.starTint = starTint; this.ambient = ambient; this.nebula = nebula; this.haze = haze;
        }

        static Theme of(StarSystemDefinition definition) {
            String id = definition.id().toLowerCase();
            if (definition.hasTag("gas_rich") || id.contains("nebula") || definition.role().equalsIgnoreCase("gas")) return NEBULA;
            if (id.contains("ice") || definition.hasTag("cold")) return ICE;
            if (id.contains("graveyard") || id.contains("shattered") || id.contains("corsair") || definition.hasTag("warzone")) return GRAVEYARD;
            if (id.contains("volcanic") || id.contains("forge") || id.contains("red_dwarf") || id.contains("red-dwarf")) return FORGE;
            return DEEP;
        }
    }
}
