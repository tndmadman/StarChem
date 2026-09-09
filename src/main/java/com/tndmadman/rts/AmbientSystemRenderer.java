package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic, non-authoritative environmental dressing for star systems.
 *
 * <p>The generated fields are cached per system definition and contain only render data.
 * They never participate in collision, selection, targeting, saves, or network state.</p>
 */
final class AmbientSystemRenderer {
    private static final double FAR_PARALLAX = 0.42;
    private static final double MID_PARALLAX = 0.68;
    private static final int MAX_DECORATIONS = 280;
    private static final Stroke THIN_STROKE = new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Stroke SOFT_STROKE = new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();

    enum Theme {
        DEEP_SPACE,
        ICE,
        WARZONE,
        NEBULA,
        GRAVEYARD,
        FORGE,
        PULSAR
    }

    private AmbientSystemRenderer() { }

    static void drawBackdrop(Graphics2D g2, StarSystemDefinition definition, double visualTime) {
        if (g2 == null || definition == null) return;
        Field field = field(definition);
        View view = View.from(g2, definition);
        int stride = densityStride(view.scale);

        drawDust(g2, field, field.farDust, view, visualTime, FAR_PARALLAX, stride, true);
        switch (field.theme) {
            case NEBULA -> drawWisps(g2, field, view, visualTime, stride);
            case PULSAR -> drawPulsarShafts(g2, definition, view, visualTime);
            case FORGE -> drawForgeHaze(g2, definition, view, visualTime);
            default -> { }
        }
    }

    static void drawForeground(Graphics2D g2, StarSystemDefinition definition, double visualTime) {
        if (g2 == null || definition == null) return;
        Field field = field(definition);
        View view = View.from(g2, definition);
        int stride = densityStride(view.scale);

        switch (field.theme) {
            case ICE -> drawIceFragments(g2, field, view, visualTime, stride);
            case WARZONE -> {
                drawDebris(g2, field, view, visualTime, stride, new Color(158, 132, 112, 70), new Color(255, 145, 72, 72));
                drawWrecks(g2, field, view, visualTime, stride, new Color(118, 126, 136, 62));
            }
            case GRAVEYARD -> {
                drawDebris(g2, field, view, visualTime, stride, new Color(125, 134, 146, 58), new Color(170, 182, 192, 42));
                drawWrecks(g2, field, view, visualTime, stride, new Color(126, 139, 151, 72));
            }
            case NEBULA -> drawGasMotes(g2, field, view, visualTime, stride);
            case FORGE -> drawForgeMotes(g2, field, view, visualTime, stride);
            case PULSAR -> drawDust(g2, field, field.midBits, view, visualTime, MID_PARALLAX, stride + 1, false);
            case DEEP_SPACE -> drawDust(g2, field, field.midBits, view, visualTime, MID_PARALLAX, stride + 1, false);
        }
        drawMeteors(g2, field, view, visualTime, stride);
    }

    static Theme themeFor(StarSystemDefinition definition) {
        if (definition == null) return Theme.DEEP_SPACE;
        String id = normalized(definition.id());
        String role = normalized(definition.role());

        if (id.contains("graveyard") || definition.hasTag("salvage") || role.equals("relic")) return Theme.GRAVEYARD;
        if (id.contains("ice") || role.equals("ice") || definition.hasTag("ice_rich")) return Theme.ICE;
        if (id.contains("warzone") || role.equals("danger") || definition.hasTag("contested") || definition.hasTag("hazardous")) return Theme.WARZONE;
        if (id.contains("nebula") || id.contains("gas_giant") || role.equals("gas") || definition.hasTag("gas_rich")) return Theme.NEBULA;
        if (id.contains("pulsar")) return Theme.PULSAR;
        if (id.contains("volcanic") || id.contains("forge") || definition.hasTag("volcanic")) return Theme.FORGE;
        return Theme.DEEP_SPACE;
    }

    static Snapshot snapshotForTest(StarSystemDefinition definition) {
        Field field = field(definition);
        return new Snapshot(field.theme, field.farDust.length, field.midBits.length,
                field.wrecks.length, field.wisps.length, field.meteors.length, field.signature);
    }

    static int densityStrideForTest(double scale) { return densityStride(scale); }

    static double screenParallaxDeltaForTest(double cameraDelta, double depth) {
        return Math.abs(cameraDelta * depth);
    }

    private static Field field(StarSystemDefinition definition) {
        StarSystemDefinition safe = definition == null ? StarSystems.defaultSystem() : definition;
        String key = safe.id() + '|' + safe.role() + '|' + safe.width() + 'x' + safe.height() + '|' + themeFor(safe);
        return FIELDS.computeIfAbsent(key, ignored -> buildField(safe));
    }

    private static Field buildField(StarSystemDefinition definition) {
        Theme theme = themeFor(definition);
        Random random = new Random(stableSeed(definition, theme));

        int farCount = switch (theme) {
            case NEBULA -> 118;
            case ICE -> 92;
            case WARZONE -> 76;
            case GRAVEYARD -> 70;
            case FORGE -> 82;
            case PULSAR -> 72;
            case DEEP_SPACE -> 88;
        };
        int midCount = switch (theme) {
            case ICE -> 74;
            case WARZONE -> 62;
            case GRAVEYARD -> 54;
            case NEBULA -> 46;
            case FORGE -> 58;
            case PULSAR -> 36;
            case DEEP_SPACE -> 42;
        };
        int wreckCount = theme == Theme.GRAVEYARD ? 16 : theme == Theme.WARZONE ? 9 : 0;
        int wispCount = theme == Theme.NEBULA ? 18 : 0;
        int meteorCount = switch (theme) {
            case WARZONE, ICE, FORGE -> 4;
            case GRAVEYARD, PULSAR -> 3;
            default -> 2;
        };

        Particle[] farDust = particles(random, farCount, definition, 0.15, 0.75, 0.55, 1.65);
        Particle[] midBits = particles(random, midCount, definition, 0.3, 1.55, 1.2, 4.2);
        Wreck[] wrecks = wrecks(random, wreckCount, definition);
        Wisp[] wisps = wisps(random, wispCount, definition);
        Meteor[] meteors = meteors(random, meteorCount, definition);

        int total = farDust.length + midBits.length + wrecks.length + wisps.length + meteors.length;
        if (total > MAX_DECORATIONS) throw new IllegalStateException("Ambient field exceeded decoration bound: " + total);

        long signature = stableSeed(definition, theme);
        signature = mix(signature, farDust.length);
        signature = mix(signature, midBits.length);
        signature = mix(signature, wrecks.length);
        signature = mix(signature, wisps.length);
        signature = mix(signature, meteors.length);
        for (Particle particle : farDust) signature = mix(signature, Double.doubleToLongBits(particle.x + particle.y + particle.phase));
        for (Wreck wreck : wrecks) signature = mix(signature, Double.doubleToLongBits(wreck.x + wreck.y + wreck.angle));
        for (Wisp wisp : wisps) signature = mix(signature, Double.doubleToLongBits(wisp.x + wisp.y + wisp.phase));

        return new Field(theme, farDust, midBits, wrecks, wisps, meteors, signature);
    }

    private static Particle[] particles(Random random, int count, StarSystemDefinition definition,
                                        double minSpeed, double maxSpeed, double minSize, double maxSize) {
        Particle[] out = new Particle[count];
        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double speed = minSpeed + random.nextDouble() * (maxSpeed - minSpeed);
            out[i] = new Particle(
                    random.nextDouble() * definition.width(),
                    random.nextDouble() * definition.height(),
                    Math.cos(angle) * speed,
                    Math.sin(angle) * speed,
                    minSize + random.nextDouble() * (maxSize - minSize),
                    random.nextDouble() * Math.PI * 2.0);
        }
        return out;
    }

    private static Wreck[] wrecks(Random random, int count, StarSystemDefinition definition) {
        Wreck[] out = new Wreck[count];
        for (int i = 0; i < count; i++) {
            out[i] = new Wreck(
                    random.nextDouble() * definition.width(),
                    random.nextDouble() * definition.height(),
                    18 + random.nextDouble() * 28,
                    random.nextDouble() * Math.PI * 2.0,
                    (random.nextDouble() - 0.5) * 0.018,
                    random.nextDouble() * Math.PI * 2.0);
        }
        return out;
    }

    private static Wisp[] wisps(Random random, int count, StarSystemDefinition definition) {
        Wisp[] out = new Wisp[count];
        for (int i = 0; i < count; i++) {
            out[i] = new Wisp(
                    random.nextDouble() * definition.width(),
                    random.nextDouble() * definition.height(),
                    120 + random.nextDouble() * 260,
                    55 + random.nextDouble() * 130,
                    (random.nextDouble() - 0.5) * 0.65,
                    (random.nextDouble() - 0.5) * 0.35,
                    random.nextDouble() * Math.PI * 2.0);
        }
        return out;
    }

    private static Meteor[] meteors(Random random, int count, StarSystemDefinition definition) {
        Meteor[] out = new Meteor[count];
        for (int i = 0; i < count; i++) {
            double angle = -Math.PI * 0.22 + (random.nextDouble() - 0.5) * 0.55;
            out[i] = new Meteor(
                    random.nextDouble() * definition.width(),
                    random.nextDouble() * definition.height(),
                    Math.cos(angle),
                    Math.sin(angle),
                    85 + random.nextDouble() * 130,
                    17 + random.nextDouble() * 34,
                    random.nextDouble() * 40.0);
        }
        return out;
    }

    private static void drawDust(Graphics2D g2, Field field, Particle[] particles, View view,
                                 double time, double parallax, int stride, boolean backdrop) {
        Color color = dustColor(field.theme, backdrop);
        g2.setColor(color);
        for (int i = 0; i < particles.length; i += Math.max(1, stride)) {
            Particle particle = particles[i];
            double x = wrap(particle.x + particle.vx * time, view.worldW);
            double y = wrap(particle.y + particle.vy * time, view.worldH);
            x = parallax(x, view.cameraX, parallax);
            y = parallax(y, view.cameraY, parallax);
            double radius = particle.size;
            if (!view.visible(x, y, radius + 2)) continue;
            int size = Math.max(1, (int)Math.round(radius));
            g2.fillOval((int)Math.round(x), (int)Math.round(y), size, size);
        }
    }

    private static void drawIceFragments(Graphics2D g2, Field field, View view, double time, int stride) {
        Stroke oldStroke = g2.getStroke();
        g2.setStroke(THIN_STROKE);
        g2.setColor(new Color(190, 235, 255, 72));
        for (int i = 0; i < field.midBits.length; i += Math.max(1, stride)) {
            Particle particle = field.midBits[i];
            double x = wrap(particle.x + particle.vx * time * 0.7, view.worldW);
            double y = wrap(particle.y + particle.vy * time * 0.7, view.worldH);
            x = parallax(x, view.cameraX, MID_PARALLAX);
            y = parallax(y, view.cameraY, MID_PARALLAX);
            double size = 2.5 + particle.size * 1.15;
            if (!view.visible(x, y, size + 3)) continue;
            double pulse = 0.72 + Math.sin(time * 0.55 + particle.phase) * 0.18;
            int r = Math.max(2, (int)Math.round(size * pulse));
            int ix = (int)Math.round(x);
            int iy = (int)Math.round(y);
            g2.drawLine(ix - r, iy, ix + r, iy);
            g2.drawLine(ix, iy - Math.max(1, r / 2), ix, iy + Math.max(1, r / 2));
        }
        g2.setStroke(oldStroke);
    }

    private static void drawDebris(Graphics2D g2, Field field, View view, double time, int stride,
                                   Color hullColor, Color emberColor) {
        Stroke oldStroke = g2.getStroke();
        g2.setStroke(THIN_STROKE);
        for (int i = 0; i < field.midBits.length; i += Math.max(1, stride)) {
            Particle particle = field.midBits[i];
            double x = wrap(particle.x + particle.vx * time, view.worldW);
            double y = wrap(particle.y + particle.vy * time, view.worldH);
            x = parallax(x, view.cameraX, MID_PARALLAX);
            y = parallax(y, view.cameraY, MID_PARALLAX);
            double size = 2 + particle.size * 1.2;
            if (!view.visible(x, y, size + 4)) continue;
            double angle = particle.phase + time * 0.04;
            int dx = (int)Math.round(Math.cos(angle) * size);
            int dy = (int)Math.round(Math.sin(angle) * size);
            int ix = (int)Math.round(x);
            int iy = (int)Math.round(y);
            g2.setColor((i % 7 == 0) ? emberColor : hullColor);
            g2.drawLine(ix - dx, iy - dy, ix + dx, iy + dy);
        }
        g2.setStroke(oldStroke);
    }

    private static void drawWrecks(Graphics2D g2, Field field, View view, double time, int stride, Color color) {
        Stroke oldStroke = g2.getStroke();
        g2.setStroke(SOFT_STROKE);
        g2.setColor(color);
        for (int i = 0; i < field.wrecks.length; i += Math.max(1, stride)) {
            Wreck wreck = field.wrecks[i];
            double x = parallax(wreck.x, view.cameraX, MID_PARALLAX);
            double y = parallax(wreck.y, view.cameraY, MID_PARALLAX);
            if (!view.visible(x, y, wreck.length + 8)) continue;
            double angle = wreck.angle + wreck.spin * time;
            double ux = Math.cos(angle);
            double uy = Math.sin(angle);
            double vx = -uy;
            double vy = ux;
            double half = wreck.length * 0.5;
            double cross = wreck.length * 0.22;
            int x1 = (int)Math.round(x - ux * half);
            int y1 = (int)Math.round(y - uy * half);
            int x2 = (int)Math.round(x + ux * half);
            int y2 = (int)Math.round(y + uy * half);
            g2.drawLine(x1, y1, x2, y2);
            int cx1 = (int)Math.round(x - vx * cross);
            int cy1 = (int)Math.round(y - vy * cross);
            int cx2 = (int)Math.round(x + vx * cross);
            int cy2 = (int)Math.round(y + vy * cross);
            g2.drawLine(cx1, cy1, cx2, cy2);
            if ((i & 1) == 0) {
                int brokenX = (int)Math.round(x + ux * half * 0.38 + vx * cross * 0.65);
                int brokenY = (int)Math.round(y + uy * half * 0.38 + vy * cross * 0.65);
                g2.drawLine((int)Math.round(x + ux * half * 0.12), (int)Math.round(y + uy * half * 0.12), brokenX, brokenY);
            }
        }
        g2.setStroke(oldStroke);
    }

    private static void drawWisps(Graphics2D g2, Field field, View view, double time, int stride) {
        for (int i = 0; i < field.wisps.length; i += Math.max(1, stride)) {
            Wisp wisp = field.wisps[i];
            double x = wrap(wisp.x + wisp.vx * time, view.worldW);
            double y = wrap(wisp.y + wisp.vy * time, view.worldH);
            x = parallax(x, view.cameraX, FAR_PARALLAX);
            y = parallax(y, view.cameraY, FAR_PARALLAX);
            if (!view.visible(x, y, Math.max(wisp.rx, wisp.ry))) continue;
            double breathe = 1.0 + Math.sin(time * 0.08 + wisp.phase) * 0.08;
            int rx = (int)Math.round(wisp.rx * breathe);
            int ry = (int)Math.round(wisp.ry * breathe);
            Color color = (i & 1) == 0 ? new Color(103, 91, 178, 15) : new Color(69, 132, 164, 13);
            g2.setColor(color);
            g2.fillOval((int)Math.round(x - rx), (int)Math.round(y - ry), rx * 2, ry * 2);
        }
    }

    private static void drawGasMotes(Graphics2D g2, Field field, View view, double time, int stride) {
        g2.setColor(new Color(160, 198, 225, 46));
        for (int i = 0; i < field.midBits.length; i += Math.max(1, stride)) {
            Particle particle = field.midBits[i];
            double x = wrap(particle.x + particle.vx * time * 0.42, view.worldW);
            double y = wrap(particle.y + particle.vy * time * 0.42, view.worldH);
            x = parallax(x, view.cameraX, MID_PARALLAX);
            y = parallax(y, view.cameraY, MID_PARALLAX);
            double radius = 1.5 + particle.size * 0.7;
            if (!view.visible(x, y, radius + 2)) continue;
            int size = Math.max(2, (int)Math.round(radius * 2));
            g2.fillOval((int)Math.round(x - radius), (int)Math.round(y - radius), size, size);
        }
    }

    private static void drawForgeHaze(Graphics2D g2, StarSystemDefinition definition, View view, double time) {
        double cx = definition.width() * 0.5;
        double cy = definition.height() * 0.5;
        cx = parallax(cx, view.cameraX, FAR_PARALLAX);
        cy = parallax(cy, view.cameraY, FAR_PARALLAX);
        double pulse = 0.92 + Math.sin(time * 0.12) * 0.08;
        int rx = (int)Math.round(720 * pulse);
        int ry = (int)Math.round(440 * pulse);
        if (!view.visible(cx, cy, rx)) return;
        g2.setColor(new Color(208, 90, 48, 10));
        g2.fillOval((int)Math.round(cx - rx), (int)Math.round(cy - ry), rx * 2, ry * 2);
    }

    private static void drawForgeMotes(Graphics2D g2, Field field, View view, double time, int stride) {
        g2.setColor(new Color(255, 139, 64, 68));
        for (int i = 0; i < field.midBits.length; i += Math.max(1, stride)) {
            Particle particle = field.midBits[i];
            double x = wrap(particle.x + particle.vx * time * 0.55, view.worldW);
            double y = wrap(particle.y - Math.abs(particle.vy + 0.5) * time * 0.9, view.worldH);
            x = parallax(x, view.cameraX, MID_PARALLAX);
            y = parallax(y, view.cameraY, MID_PARALLAX);
            if (!view.visible(x, y, particle.size + 2)) continue;
            int size = Math.max(1, (int)Math.round(1 + particle.size * 0.55));
            g2.fillOval((int)Math.round(x), (int)Math.round(y), size, size);
        }
    }

    private static void drawPulsarShafts(Graphics2D g2, StarSystemDefinition definition, View view, double time) {
        double cx = parallax(definition.width() * 0.5, view.cameraX, FAR_PARALLAX);
        double cy = parallax(definition.height() * 0.5, view.cameraY, FAR_PARALLAX);
        if (!view.visible(cx, cy, 2400)) return;
        Stroke oldStroke = g2.getStroke();
        g2.setStroke(SOFT_STROKE);
        g2.setColor(new Color(140, 205, 255, 18));
        double angle = time * 0.035;
        for (int i = 0; i < 3; i++) {
            double a = angle + i * Math.PI / 3.0;
            double dx = Math.cos(a) * 2200;
            double dy = Math.sin(a) * 2200;
            g2.drawLine((int)Math.round(cx - dx), (int)Math.round(cy - dy),
                    (int)Math.round(cx + dx), (int)Math.round(cy + dy));
        }
        g2.setStroke(oldStroke);
    }

    private static void drawMeteors(Graphics2D g2, Field field, View view, double time, int stride) {
        if (stride >= 3) return;
        Stroke oldStroke = g2.getStroke();
        g2.setStroke(THIN_STROKE);
        Color tail = meteorColor(field.theme);
        for (Meteor meteor : field.meteors) {
            double phase = positiveModulo(time + meteor.phase, meteor.period);
            double activeSeconds = 0.85;
            if (phase > activeSeconds) continue;
            double travel = phase / activeSeconds;
            double x = wrap(meteor.x + meteor.dx * travel * meteor.length * 1.6, view.worldW);
            double y = wrap(meteor.y + meteor.dy * travel * meteor.length * 1.6, view.worldH);
            x = parallax(x, view.cameraX, MID_PARALLAX);
            y = parallax(y, view.cameraY, MID_PARALLAX);
            if (!view.visible(x, y, meteor.length + 8)) continue;
            double tailLength = meteor.length * (0.55 + 0.45 * (1.0 - travel));
            int tx = (int)Math.round(x - meteor.dx * tailLength);
            int ty = (int)Math.round(y - meteor.dy * tailLength);
            int hx = (int)Math.round(x);
            int hy = (int)Math.round(y);
            g2.setColor(tail);
            g2.drawLine(tx, ty, hx, hy);
            g2.fillOval(hx - 1, hy - 1, 3, 3);
        }
        g2.setStroke(oldStroke);
    }

    private static Color dustColor(Theme theme, boolean backdrop) {
        return switch (theme) {
            case ICE -> backdrop ? new Color(180, 225, 255, 38) : new Color(205, 238, 255, 50);
            case WARZONE -> backdrop ? new Color(171, 151, 136, 28) : new Color(180, 151, 126, 42);
            case NEBULA -> backdrop ? new Color(155, 150, 220, 34) : new Color(170, 195, 225, 42);
            case GRAVEYARD -> backdrop ? new Color(151, 160, 171, 26) : new Color(165, 175, 184, 38);
            case FORGE -> backdrop ? new Color(210, 126, 78, 30) : new Color(230, 145, 78, 45);
            case PULSAR -> backdrop ? new Color(145, 205, 245, 34) : new Color(178, 225, 255, 48);
            case DEEP_SPACE -> backdrop ? new Color(170, 196, 220, 30) : new Color(185, 205, 224, 40);
        };
    }

    private static Color meteorColor(Theme theme) {
        return switch (theme) {
            case ICE -> new Color(205, 242, 255, 75);
            case WARZONE, FORGE -> new Color(255, 155, 86, 78);
            case NEBULA -> new Color(185, 205, 245, 58);
            case GRAVEYARD -> new Color(175, 185, 195, 52);
            case PULSAR -> new Color(185, 232, 255, 72);
            case DEEP_SPACE -> new Color(205, 220, 235, 55);
        };
    }

    private static int densityStride(double scale) {
        double normalized = Math.abs(scale);
        if (!Double.isFinite(normalized) || normalized <= 0.01) return 3;
        if (normalized < 0.43) return 3;
        if (normalized < 0.56) return 2;
        return 1;
    }

    private static double parallax(double base, double camera, double depth) {
        return base + camera * (1.0 - depth);
    }

    private static double wrap(double value, double limit) {
        if (!Double.isFinite(value) || !Double.isFinite(limit) || limit <= 0) return 0;
        double wrapped = value % limit;
        return wrapped < 0 ? wrapped + limit : wrapped;
    }

    private static double positiveModulo(double value, double modulus) {
        if (modulus <= 0) return 0;
        double out = value % modulus;
        return out < 0 ? out + modulus : out;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static long stableSeed(StarSystemDefinition definition, Theme theme) {
        long seed = 0x9E3779B97F4A7C15L;
        seed = mix(seed, definition.id().hashCode());
        seed = mix(seed, definition.role().hashCode());
        seed = mix(seed, definition.width());
        seed = mix(seed, definition.height());
        seed = mix(seed, theme.ordinal());
        for (String tag : definition.tags().stream().sorted().toList()) seed = mix(seed, tag.hashCode());
        return seed;
    }

    private static long mix(long value, long input) {
        long z = value ^ (input + 0x9E3779B97F4A7C15L + (value << 6) + (value >>> 2));
        z ^= z >>> 30;
        z *= 0xBF58476D1CE4E5B9L;
        z ^= z >>> 27;
        z *= 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    record Snapshot(Theme theme, int farDust, int midBits, int wrecks, int wisps, int meteors, long signature) {
        int totalDecorations() { return farDust + midBits + wrecks + wisps + meteors; }
    }

    private record Field(Theme theme, Particle[] farDust, Particle[] midBits, Wreck[] wrecks,
                         Wisp[] wisps, Meteor[] meteors, long signature) { }

    private record Particle(double x, double y, double vx, double vy, double size, double phase) { }

    private record Wreck(double x, double y, double length, double angle, double spin, double phase) { }

    private record Wisp(double x, double y, double rx, double ry, double vx, double vy, double phase) { }

    private record Meteor(double x, double y, double dx, double dy, double length, double period, double phase) { }

    private record View(double cameraX, double cameraY, double scale,
                        double minX, double minY, double maxX, double maxY,
                        double worldW, double worldH) {
        static View from(Graphics2D g2, StarSystemDefinition definition) {
            AffineTransform transform = g2.getTransform();
            double sx = transform.getScaleX();
            double sy = transform.getScaleY();
            double scale = Math.max(Math.abs(sx), Math.abs(sy));
            double cameraX = Math.abs(sx) > 0.000001 ? -transform.getTranslateX() / sx : 0;
            double cameraY = Math.abs(sy) > 0.000001 ? -transform.getTranslateY() / sy : 0;
            Rectangle clip = g2.getClipBounds();
            if (clip == null) {
                return new View(cameraX, cameraY, scale, 0, 0,
                        definition.width(), definition.height(), definition.width(), definition.height());
            }
            double margin = 120;
            return new View(cameraX, cameraY, scale,
                    clip.getMinX() - margin, clip.getMinY() - margin,
                    clip.getMaxX() + margin, clip.getMaxY() + margin,
                    definition.width(), definition.height());
        }

        boolean visible(double x, double y, double radius) {
            return x + radius >= minX && x - radius <= maxX
                    && y + radius >= minY && y - radius <= maxY;
        }
    }
}
