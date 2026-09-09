package com.tndmadman.rts;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Render-only environmental dressing for authoritative resource nodes.
 *
 * Decorative rocks and gas volumes are cached sprites. They are deliberately not
 * ResourceNodes, so they cannot affect targeting, harvesting, AI, respawn, saves,
 * fog-of-war, or network replication.
 */
final class ResourceFieldRenderer {
    private static final int MAX_CACHE_ENTRIES = 192;
    private static final Map<SpriteKey, BufferedImage> FIELD_CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<SpriteKey, BufferedImage> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };

    private ResourceFieldRenderer() { }

    static void draw(Graphics2D source, ResourceNode node, boolean selected) {
        if (source == null || node == null || !node.active) return;
        Lod lod = lodFor(source);
        boolean backdrop = shouldDrawBackdrop(node, lod);
        double cullRadius = backdrop ? fieldRadius(node.kind, lod) : nodeVisualRadius(node) + 18;
        if (!RenderCulling.visible(source, node.x, node.y, cullRadius)) return;

        Graphics2D g = (Graphics2D) source.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        if (backdrop) drawFieldSprite(g, node, lod);
        if (node.kind == NodeKind.GAS_CLOUD) drawHarvestGas(g, node, selected);
        else drawHarvestRock(g, node, selected);
        drawAmountBar(g, node, selected);
        g.dispose();
    }

    private static void drawFieldSprite(Graphics2D g, ResourceNode node, Lod lod) {
        int variant = Math.floorMod(node.id * 37 + node.material.ordinal() * 11, 24);
        SpriteKey key = new SpriteKey(node.kind, node.material, variant, depletionBucket(node), lod);
        BufferedImage sprite = cachedSprite(key);
        double width = sprite.getWidth();
        double height = sprite.getHeight();
        g.drawImage(sprite,
                (int)Math.round(node.x - width / 2.0),
                (int)Math.round(node.y - height / 2.0), null);
    }

    private static synchronized BufferedImage cachedSprite(SpriteKey key) {
        BufferedImage cached = FIELD_CACHE.get(key);
        if (cached != null) return cached;
        BufferedImage created = key.kind == NodeKind.GAS_CLOUD ? createGasField(key) : createAsteroidField(key);
        FIELD_CACHE.put(key, created);
        return created;
    }

    private static BufferedImage createAsteroidField(SpriteKey key) {
        int size = switch (key.lod) {
            case FAR -> 64;
            case MEDIUM -> 84;
            case NEAR -> 104;
        };
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Random random = new Random(seed(key));
        double cx = size / 2.0;
        double cy = size / 2.0;
        int count = switch (key.lod) {
            case FAR -> 5;
            case MEDIUM -> 8;
            case NEAR -> 12;
        };
        double density = 1.0 - key.depletionBucket * 0.15;
        count = Math.max(3, (int)Math.round(count * density));

        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = size * (0.16 + random.nextDouble() * 0.29);
            double x = cx + Math.cos(angle) * distance;
            double y = cy + Math.sin(angle) * distance * 0.78;
            double radius = 2.2 + random.nextDouble() * (key.lod == Lod.NEAR ? 5.8 : 4.0);
            if (key.lod == Lod.NEAR && key.variant % 7 == 0 && i == 0) radius *= 1.75;
            drawAsteroid(g, x, y, radius, random.nextLong(), key.material, false, 0.48f);
        }

        int fragments = switch (key.lod) {
            case FAR -> 5;
            case MEDIUM -> 12;
            case NEAR -> 20;
        };
        g.setColor(new Color(145, 142, 132, 82));
        for (int i = 0; i < fragments; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = size * (0.12 + random.nextDouble() * 0.38);
            int x = (int)Math.round(cx + Math.cos(angle) * distance);
            int y = (int)Math.round(cy + Math.sin(angle) * distance * 0.78);
            int d = random.nextDouble() < 0.18 ? 2 : 1;
            g.fillOval(x, y, d, d);
        }
        g.dispose();
        return image;
    }

    private static BufferedImage createGasField(SpriteKey key) {
        int width = switch (key.lod) {
            case FAR -> 126;
            case MEDIUM -> 154;
            case NEAR -> 184;
        };
        int height = switch (key.lod) {
            case FAR -> 82;
            case MEDIUM -> 104;
            case NEAR -> 124;
        };
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        Random random = new Random(seed(key));
        Color theme = gasTheme(key.material);
        double density = 1.0 - key.depletionBucket * 0.18;
        int lobes = switch (key.lod) {
            case FAR -> 4;
            case MEDIUM -> 7;
            case NEAR -> 10;
        };
        lobes = Math.max(3, (int)Math.round(lobes * density));

        for (int i = 0; i < lobes; i++) {
            float x = (float)(width * (0.22 + random.nextDouble() * 0.56));
            float y = (float)(height * (0.22 + random.nextDouble() * 0.56));
            float radius = (float)(height * (0.22 + random.nextDouble() * 0.26));
            int centerAlpha = (int)Math.round((38 + random.nextInt(30)) * density);
            Color center = alpha(lighten(theme, 0.12 + random.nextDouble() * 0.18), centerAlpha);
            Color edge = alpha(theme, 0);
            RadialGradientPaint paint = new RadialGradientPaint(
                    new Point2D.Float(x, y), radius,
                    new float[]{0f, 0.46f, 1f},
                    new Color[]{center, alpha(theme, Math.max(4, centerAlpha / 3)), edge});
            g.setPaint(paint);
            g.fill(new Ellipse2D.Double(x - radius * 1.38, y - radius * 0.82,
                    radius * 2.76, radius * 1.64));
        }

        if (key.lod != Lod.FAR) {
            g.setComposite(AlphaComposite.SrcOver.derive((float)(0.20 * density)));
            g.setColor(lighten(theme, 0.28));
            g.setStroke(new BasicStroke(key.lod == Lod.NEAR ? 2.2f : 1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int wisps = key.lod == Lod.NEAR ? 5 : 3;
            for (int i = 0; i < wisps; i++) {
                double x = width * (0.08 + random.nextDouble() * 0.58);
                double y = height * (0.18 + random.nextDouble() * 0.60);
                double w = width * (0.30 + random.nextDouble() * 0.38);
                double h = height * (0.18 + random.nextDouble() * 0.30);
                g.draw(new Arc2D.Double(x, y, w, h, random.nextInt(180), 90 + random.nextInt(90), Arc2D.OPEN));
            }
        }
        g.dispose();
        return image;
    }

    private static void drawHarvestRock(Graphics2D g, ResourceNode node, boolean selected) {
        double radius = nodeVisualRadius(node);
        long seed = mix64(((long)node.id << 32) ^ node.material.ordinal() * 0x9E3779B97F4A7C15L);
        drawAsteroid(g, node.x, node.y, radius, seed, node.material, true, 1.0f);
        if (selected) drawSelection(g, node.x, node.y, radius + 7);
    }

    private static void drawAsteroid(Graphics2D g, double cx, double cy, double radius, long seed,
                                     Material material, boolean harvestable, float accentStrength) {
        Random random = new Random(seed);
        int points = 9 + random.nextInt(5);
        double rotation = random.nextDouble() * Math.PI * 2;
        Path2D path = new Path2D.Double();
        for (int i = 0; i < points; i++) {
            double angle = rotation + i * Math.PI * 2 / points;
            double wobble = 0.72 + random.nextDouble() * 0.34;
            double x = cx + Math.cos(angle) * radius * wobble;
            double y = cy + Math.sin(angle) * radius * wobble;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.closePath();

        Color stone = stoneColor(material);
        g.setPaint(new GradientPaint((float)(cx - radius), (float)(cy - radius), lighten(stone, 0.24),
                (float)(cx + radius), (float)(cy + radius), darken(stone, 0.33)));
        g.fill(path);
        g.setColor(new Color(28, 30, 31, harvestable ? 230 : 165));
        g.setStroke(new BasicStroke(harvestable ? 1.25f : 0.8f));
        g.draw(path);

        int craters = harvestable ? 2 + random.nextInt(2) : random.nextInt(3);
        for (int i = 0; i < craters; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double d = radius * random.nextDouble() * 0.48;
            double rr = radius * (0.11 + random.nextDouble() * 0.13);
            double x = cx + Math.cos(a) * d;
            double y = cy + Math.sin(a) * d;
            g.setColor(alpha(darken(stone, 0.50), harvestable ? 190 : 125));
            g.fill(new Ellipse2D.Double(x - rr, y - rr * 0.70, rr * 2, rr * 1.4));
            g.setColor(alpha(lighten(stone, 0.30), harvestable ? 120 : 70));
            g.draw(new Arc2D.Double(x - rr, y - rr * 0.70, rr * 2, rr * 1.4, 205, 145, Arc2D.OPEN));
        }

        if (harvestable || random.nextDouble() < 0.42) {
            Color seam = alpha(material.color, (int)(harvestable ? 215 : 105 * accentStrength));
            g.setColor(seam);
            g.setStroke(new BasicStroke(harvestable ? 1.25f : 0.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double a = random.nextDouble() * Math.PI * 2;
            double x1 = cx + Math.cos(a) * radius * 0.62;
            double y1 = cy + Math.sin(a) * radius * 0.62;
            double x2 = cx - Math.cos(a) * radius * 0.34;
            double y2 = cy - Math.sin(a) * radius * 0.34;
            double mx = (x1 + x2) * 0.5 + Math.cos(a + Math.PI / 2) * radius * 0.16;
            double my = (y1 + y2) * 0.5 + Math.sin(a + Math.PI / 2) * radius * 0.16;
            Path2D fracture = new Path2D.Double();
            fracture.moveTo(x1, y1);
            fracture.lineTo(mx, my);
            fracture.lineTo(x2, y2);
            g.draw(fracture);
        }

        if (harvestable) {
            g.setColor(alpha(lighten(stone, 0.42), 110));
            g.setStroke(new BasicStroke(0.8f));
            g.draw(new Arc2D.Double(cx - radius * 0.70, cy - radius * 0.72,
                    radius * 1.25, radius * 1.08, 195, 95, Arc2D.OPEN));
        }
    }

    private static void drawHarvestGas(Graphics2D g, ResourceNode node, boolean selected) {
        double radius = nodeVisualRadius(node) * 1.12;
        Color theme = gasTheme(node.material);
        double pct = amountPercent(node);
        int alpha = (int)Math.round(92 + 70 * pct);
        g.setColor(alpha(theme, alpha));
        g.setStroke(new BasicStroke(1.25f));
        g.draw(new Ellipse2D.Double(node.x - radius, node.y - radius * 0.72, radius * 2, radius * 1.44));
        g.setColor(alpha(lighten(theme, 0.35), 90 + (int)Math.round(80 * pct)));
        g.fill(new Ellipse2D.Double(node.x - radius * 0.24, node.y - radius * 0.18,
                radius * 0.48, radius * 0.36));
        g.setColor(alpha(theme, 195));
        g.setStroke(new BasicStroke(1.05f));
        g.drawLine((int)Math.round(node.x - radius * 0.46), (int)Math.round(node.y),
                (int)Math.round(node.x - radius * 0.18), (int)Math.round(node.y));
        g.drawLine((int)Math.round(node.x + radius * 0.18), (int)Math.round(node.y),
                (int)Math.round(node.x + radius * 0.46), (int)Math.round(node.y));
        if (selected) drawSelection(g, node.x, node.y, radius + 7);
    }

    private static void drawSelection(Graphics2D g, double x, double y, double radius) {
        g.setColor(new Color(255, 245, 140, 215));
        g.setStroke(new BasicStroke(1.8f));
        g.draw(new Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2));
    }

    private static void drawAmountBar(Graphics2D g, ResourceNode node, boolean selected) {
        double radius = nodeVisualRadius(node);
        int w = Math.max(14, (int)Math.round(radius * 3.5));
        int h = selected ? 4 : 3;
        int bx = (int)Math.round(node.x - w / 2.0);
        int by = (int)Math.round(node.y + radius + 6);
        double pct = amountPercent(node);
        g.setColor(new Color(0, 0, 0, selected ? 165 : 105));
        g.fillRoundRect(bx, by, w, h, 3, 3);
        g.setColor(alpha(node.material.color, selected ? 235 : 170));
        g.fillRoundRect(bx, by, (int)Math.round(w * pct), h, 3, 3);
    }

    private static double nodeVisualRadius(ResourceNode node) {
        double pct = amountPercent(node);
        double base = Math.max(5.5, node.radius * 1.65);
        return base * (0.58 + 0.42 * Math.sqrt(pct));
    }

    private static double amountPercent(ResourceNode node) {
        if (node.maxAmount <= 0 || !Double.isFinite(node.amount)) return 0;
        return Math.max(0, Math.min(1, node.amount / node.maxAmount));
    }

    private static int depletionBucket(ResourceNode node) {
        double pct = amountPercent(node);
        if (pct > 0.72) return 0;
        if (pct > 0.42) return 1;
        if (pct > 0.18) return 2;
        return 3;
    }

    private static boolean shouldDrawBackdrop(ResourceNode node, Lod lod) {
        int stride;
        if (node.kind == NodeKind.GAS_CLOUD) stride = switch (lod) {
            case FAR -> 6;
            case MEDIUM -> 3;
            case NEAR -> 2;
        };
        else stride = lod == Lod.FAR ? 2 : 1;
        return Math.floorMod(node.id * 17 + node.material.ordinal(), stride) == 0;
    }

    private static double fieldRadius(NodeKind kind, Lod lod) {
        if (kind == NodeKind.GAS_CLOUD) return switch (lod) {
            case FAR -> 72;
            case MEDIUM -> 88;
            case NEAR -> 104;
        };
        return switch (lod) {
            case FAR -> 38;
            case MEDIUM -> 48;
            case NEAR -> 58;
        };
    }

    private static Lod lodFor(Graphics2D g) {
        double sx = Math.abs(g.getTransform().getScaleX());
        double sy = Math.abs(g.getTransform().getScaleY());
        double scale = Math.max(sx, sy);
        if (!Double.isFinite(scale) || scale <= 0) return Lod.NEAR;
        if (scale < 0.45) return Lod.FAR;
        if (scale < 0.60) return Lod.MEDIUM;
        return Lod.NEAR;
    }

    private static Color stoneColor(Material material) {
        Color accent = material.color;
        int r = clamp((int)Math.round(69 * 0.78 + accent.getRed() * 0.22));
        int g = clamp((int)Math.round(70 * 0.78 + accent.getGreen() * 0.22));
        int b = clamp((int)Math.round(72 * 0.78 + accent.getBlue() * 0.22));
        return new Color(r, g, b);
    }

    private static Color gasTheme(Material material) {
        Color c = material.color;
        return new Color(
                clamp((int)Math.round(c.getRed() * 0.88 + 22)),
                clamp((int)Math.round(c.getGreen() * 0.88 + 26)),
                clamp((int)Math.round(c.getBlue() * 0.92 + 28)));
    }

    private static Color lighten(Color color, double amount) {
        double a = Math.max(0, Math.min(1, amount));
        return new Color(
                clamp((int)Math.round(color.getRed() + (255 - color.getRed()) * a)),
                clamp((int)Math.round(color.getGreen() + (255 - color.getGreen()) * a)),
                clamp((int)Math.round(color.getBlue() + (255 - color.getBlue()) * a)));
    }

    private static Color darken(Color color, double amount) {
        double a = Math.max(0, Math.min(1, amount));
        return new Color(
                clamp((int)Math.round(color.getRed() * (1 - a))),
                clamp((int)Math.round(color.getGreen() * (1 - a))),
                clamp((int)Math.round(color.getBlue() * (1 - a))));
    }

    private static Color alpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), clamp(alpha));
    }

    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }

    private static long seed(SpriteKey key) {
        long value = key.variant * 0x9E3779B97F4A7C15L;
        value ^= ((long)key.material.ordinal() + 1) * 0xBF58476D1CE4E5B9L;
        value ^= ((long)key.kind.ordinal() + 1) * 0x94D049BB133111EBL;
        value ^= ((long)key.depletionBucket + 1) * 0xD6E8FEB86659FD93L;
        value ^= ((long)key.lod.ordinal() + 1) * 0xA0761D6478BD642FL;
        return mix64(value);
    }

    private static long mix64(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return value;
    }

    static synchronized int cacheSizeForTesting() { return FIELD_CACHE.size(); }
    static synchronized void clearCacheForTesting() { FIELD_CACHE.clear(); }
    static int maxCacheEntriesForTesting() { return MAX_CACHE_ENTRIES; }

    private enum Lod { FAR, MEDIUM, NEAR }
    private record SpriteKey(NodeKind kind, Material material, int variant, int depletionBucket, Lod lod) { }
}
