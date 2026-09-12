package com.tndmadman.rts;

import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

final class CelestialSystem {
    private final List<Body> bodies = new ArrayList<>();
    private final StarSystemDefinition definition;
    private final double sunX;
    private final double sunY;
    private final SpaceBackgroundRenderer background;
    private final String visualSystemId;
    private final SystemVisualDefinition systemVisual;
    private double visualTime;

    CelestialSystem(int worldW, int worldH, Random random) {
        this(StarSystems.defaultSystem(), random);
    }

    CelestialSystem(StarSystemDefinition definition, Random random) {
        this(definition, random, 0, 0);
    }

    CelestialSystem(StarSystemDefinition definition, Random random, double offsetX, double offsetY) {
        this.definition = definition == null ? StarSystems.defaultSystem() : definition;
        Random source = random == null ? new Random(this.definition.id().hashCode()) : random;
        visualSystemId = this.definition.id();
        systemVisual = VisualCatalog.system(visualSystemId);
        sunX = offsetX + this.definition.width() / 2.0;
        sunY = offsetY + this.definition.height() / 2.0;
        background = new SpaceBackgroundRenderer(this.definition);
        buildBodies(this.definition, source);
        // Surface/detail generation is deterministic and expensive. Materialize it while the
        // client-side system is being constructed rather than on the first frame that sees a body.
        // Dedicated/headless simulations do not allocate presentation sprites.
        if (!GraphicsEnvironment.isHeadless()) prewarmBodySprites();
        update(0);
    }

    private void buildBodies(StarSystemDefinition definition, Random random) {
        Map<String, Body> byId = new LinkedHashMap<>();
        for (CelestialBodyDefinition bodyDef : definition.bodies()) {
            Body parent = bodyDef.parentId() == null ? null : byId.get(bodyDef.parentId());
            double x = parent == null ? sunX : 0;
            double y = parent == null ? sunY : 0;
            double angle = bodyDef.orbitRadius() <= 0 ? 0 : random.nextDouble() * Math.PI * 2;
            CatalogCelestialVisualDefinition catalogVisual = VisualCatalog.celestial(visualSystemId, bodyDef.id());
            CelestialVisualDefinition visual = applyCatalogMetadata(
                    CelestialVisualCatalog.resolve(visualSystemId, bodyDef), catalogVisual);
            long detailSeed = detailSeed(visualSystemId, bodyDef.id(), visual.id(), angle)
                    ^ catalogSeed(catalogVisual);
            Body body = new Body(bodyDef.id(), bodyDef.name(), parent, x, y, bodyDef.orbitRadius(), angle,
                    bodyDef.orbitSpeed(), bodyDef.radius(), catalogVisual.colorOr(bodyDef.color()), visual, detailSeed);
            bodies.add(body);
            byId.put(bodyDef.id(), body);
        }
        if (bodies.isEmpty()) {
            CelestialBodyDefinition fallbackBody =
                    new CelestialBodyDefinition("sun", "Sun", null, 0, 210, 0, new Color(255, 205, 80));
            CatalogCelestialVisualDefinition catalogVisual = VisualCatalog.celestial(visualSystemId, "sun");
            CelestialVisualDefinition visual = applyCatalogMetadata(
                    CelestialVisualCatalog.resolve(visualSystemId, fallbackBody), catalogVisual);
            Body sun = new Body("sun", "Sun", null, sunX, sunY, 0, 0, 0, 210,
                    catalogVisual.colorOr(fallbackBody.color()), visual,
                    detailSeed(visualSystemId, "sun", visual.id(), 0) ^ catalogSeed(catalogVisual));
            bodies.add(sun);
        }
    }

    private void prewarmBodySprites() {
        for (Body body : bodies) {
            try {
                CelestialSpriteCache.sprite(body.visual, body.detailSeed);
            } catch (RuntimeException ex) {
                // Rendering has the same lazy sprite lookup and can retry; presentation-cache
                // failures must never prevent a system from loading.
            }
        }
    }

    void update(double dt) {
        if (Double.isFinite(dt)) visualTime = Math.max(0, visualTime + dt);
        for (Body body : bodies) if (body.parent != null) body.update(dt);
    }

    void draw(Graphics2D g2) {
        Graphics2D c = (Graphics2D) g2.create();
        c.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        background.draw(c);
        AmbientSystemRenderer.drawBackdrop(c, definition, visualTime);
        c.setStroke(new BasicStroke(1f));
        for (Body body : bodies) if (body.parent != null) drawOrbit(c, body);
        Body light = primaryLight();
        double lightX = light == null ? sunX : light.x;
        double lightY = light == null ? sunY : light.y;
        for (Body body : bodies) body.draw(c, lightX, lightY);
        AmbientSystemRenderer.drawForeground(c, definition, visualTime);
        c.dispose();
    }

    double sunX() { return sunX; }
    double sunY() { return sunY; }
    SystemVisualDefinition systemVisualForTest() { return systemVisual; }
    long backgroundSeedForTest() { return background.seedForTest(); }

    private Body primaryLight() {
        for (Body body : bodies) if (body.visual.visualClass() == CelestialVisualClass.STAR && body.parent == null) return body;
        for (Body body : bodies) if (body.visual.visualClass() == CelestialVisualClass.STAR) return body;
        return bodies.isEmpty() ? null : bodies.get(0);
    }

    private void drawOrbit(Graphics2D g2, Body body) {
        double cx = body.parent.x;
        double cy = body.parent.y;
        int d = (int)Math.round(body.orbitRadius * 2);
        g2.setColor(systemVisual.orbitColor(body.parent.parent == null ? 0 : -10));
        g2.drawOval((int)Math.round(cx - body.orbitRadius), (int)Math.round(cy - body.orbitRadius), d, d);
    }

    private static CelestialVisualDefinition applyCatalogMetadata(
            CelestialVisualDefinition authored, CatalogCelestialVisualDefinition catalog) {
        if (authored == null || catalog == null || catalog.equals(CatalogCelestialVisualDefinition.FALLBACK)) return authored;
        Color primary = catalog.colorOr(authored.primary());
        if (primary.equals(authored.primary())) return authored;
        return new CelestialVisualDefinition(
                authored.id(), authored.visualClass(), primary, authored.secondary(), authored.accent(),
                authored.atmosphere(), authored.atmosphereStrength(), authored.cloudCoverage(),
                authored.ringInnerRadius(), authored.ringOuterRadius(), authored.ringFlattening(),
                authored.ringAngle(), authored.ringColor(), authored.emissive());
    }

    private static long catalogSeed(CatalogCelestialVisualDefinition catalog) {
        return catalog == null || catalog.equals(CatalogCelestialVisualDefinition.FALLBACK) ? 0L : catalog.seed();
    }

    private static long detailSeed(String systemId, String bodyId, String visualId, double seedAngle) {
        long hash = 0xcbf29ce484222325L;
        hash = hashText(hash, systemId);
        hash = hashText(hash, bodyId);
        hash = hashText(hash, visualId);
        hash ^= Double.doubleToLongBits(seedAngle);
        hash *= 0x100000001b3L;
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53L;
        return hash ^ (hash >>> 33);
    }

    private static long hashText(long hash, String value) {
        String text = value == null ? "" : value;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static final class Body {
        final String id;
        final String name;
        final Body parent;
        final double orbitRadius;
        final double orbitSpeed;
        final double radius;
        final Color color;
        final CelestialVisualDefinition visual;
        final long detailSeed;
        double x;
        double y;
        double angle;

        Body(String id, String name, Body parent, double x, double y, double orbitRadius, double angle,
             double orbitSpeed, double radius, Color color, CelestialVisualDefinition visual, long detailSeed) {
            this.id = id;
            this.name = name;
            this.parent = parent;
            this.x = x;
            this.y = y;
            this.orbitRadius = orbitRadius;
            this.angle = angle;
            this.orbitSpeed = orbitSpeed;
            this.radius = radius;
            this.color = color;
            this.visual = visual;
            this.detailSeed = detailSeed;
        }

        void update(double dt) {
            angle += orbitSpeed * dt;
            x = parent.x + Math.cos(angle) * orbitRadius;
            y = parent.y + Math.sin(angle) * orbitRadius;
        }

        void draw(Graphics2D g2, double lightX, double lightY) {
            CelestialRenderer.draw(g2, name, x, y, radius, lightX, lightY, visual, detailSeed);
        }
    }
}
