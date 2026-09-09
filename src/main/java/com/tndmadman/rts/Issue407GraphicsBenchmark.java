package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Headless comparison harness for issue #407. The legacy pass intentionally mirrors the
 * pre-overhaul presentation (flat field/grid, simple body discs, old ShipShape hulls,
 * generic station hexes and simple gas puffs) while the current pass uses production
 * vertical-slice renderers. It is a relative regression signal, not a hardware FPS promise.
 */
final class Issue407GraphicsBenchmark {
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int WARMUP_FRAMES = 24;
    private static final int SAMPLE_FRAMES = 90;
    private static final String[] SHIPS = {"prospector", "frigate", "cruiser", "battleship", "titan"};
    private static final String[] STATIONS = {"outpost", "shipyard", "manufacturing"};

    private Issue407GraphicsBenchmark() { }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        StarSystemDefinition definition = StarSystems.get("nebula_expanse");
        if (definition == null || !"nebula_expanse".equals(definition.id())) {
            throw new IllegalStateException("Nebula Expanse system definition is unavailable");
        }
        if (SystemVisualProfiles.forSystem(definition).isEmpty()) {
            throw new IllegalStateException("Nebula Expanse visual profile is unavailable");
        }
        for (String id : SHIPS) {
            ShipType type = Rules.ship(id);
            if (!ShowcaseShipRenderer.supports(type) || ShowcaseShipRenderer.create(type) == null) {
                throw new IllegalStateException("Missing authored issue #407 ship silhouette: " + id);
            }
        }
        for (String id : STATIONS) Rules.base(id);

        Scene scene = new Scene(definition);
        for (int i = 0; i < WARMUP_FRAMES; i++) {
            scene.renderLegacy();
            scene.renderCurrent();
        }

        long[] legacy = new long[SAMPLE_FRAMES];
        long[] current = new long[SAMPLE_FRAMES];
        for (int i = 0; i < SAMPLE_FRAMES; i++) {
            // Alternate order to reduce transient CI scheduling/JIT bias.
            if ((i & 1) == 0) {
                legacy[i] = timed(scene::renderLegacy);
                current[i] = timed(scene::renderCurrent);
            } else {
                current[i] = timed(scene::renderCurrent);
                legacy[i] = timed(scene::renderLegacy);
            }
        }

        Metrics before = Metrics.of(legacy);
        Metrics after = Metrics.of(current);
        double delta = before.meanMs <= 0 ? 0 : ((after.meanMs / before.meanMs) - 1.0) * 100.0;
        double ratio = before.meanMs <= 0 ? 0 : after.meanMs / before.meanMs;

        System.out.printf("ISSUE407_GRAPHICS legacy_mean_ms=%.3f legacy_p95_ms=%.3f current_mean_ms=%.3f current_p95_ms=%.3f delta_pct=%+.1f ratio=%.3f samples=%d%n",
                before.meanMs, before.p95Ms, after.meanMs, after.p95Ms, delta, ratio, SAMPLE_FRAMES);
        System.out.println("Issue #407 graphics benchmark completed. Treat values as relative CI comparison data, not an end-user FPS guarantee.");
    }

    private static long timed(Runnable runnable) {
        long start = System.nanoTime();
        runnable.run();
        return System.nanoTime() - start;
    }

    private static final class Scene {
        private final BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        private final StarSystemDefinition definition;
        private final CelestialSystem celestial;
        private final List<ResourceNode> gas;
        private final List<ShipType> ships;
        private final List<Base> stations;

        Scene(StarSystemDefinition definition) {
            this.definition = definition;
            this.celestial = new CelestialSystem(definition, new Random(407));
            this.gas = buildGas();
            this.ships = Arrays.stream(SHIPS).map(Rules::ship).toList();
            this.stations = Arrays.stream(STATIONS)
                    .map(id -> new Base("issue407-" + id, "SOLO", id, 0, 0)).toList();
        }

        void renderCurrent() {
            Graphics2D g = beginFrame();
            drawCurrentEnvironment(g);
            drawCurrentGas(g);
            drawCurrentShips(g);
            drawCurrentStations(g);
            g.dispose();
        }

        void renderLegacy() {
            Graphics2D g = beginFrame();
            drawLegacyEnvironment(g);
            drawLegacyGas(g);
            drawLegacyShips(g);
            drawLegacyStations(g);
            g.dispose();
        }

        private Graphics2D beginFrame() {
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(5, 8, 14));
            g.fillRect(0, 0, WIDTH, HEIGHT);
            return g;
        }

        private void drawCurrentEnvironment(Graphics2D g) {
            Graphics2D world = (Graphics2D)g.create();
            double scale = Math.min(WIDTH / (double)definition.width(), HEIGHT / (double)definition.height());
            scale *= 0.94;
            double visibleW = WIDTH / scale;
            double visibleH = HEIGHT / scale;
            world.scale(scale, scale);
            world.translate((visibleW - definition.width()) * 0.5, (visibleH - definition.height()) * 0.5);
            celestial.draw(world);
            world.dispose();
        }

        private void drawLegacyEnvironment(Graphics2D g) {
            g.setColor(new Color(8, 12, 20));
            g.fillRect(0, 0, WIDTH, HEIGHT);
            g.setColor(new Color(38, 52, 68, 90));
            g.setStroke(new BasicStroke(1f));
            for (int x = 0; x < WIDTH; x += 80) g.drawLine(x, 0, x, HEIGHT);
            for (int y = 0; y < HEIGHT; y += 80) g.drawLine(0, y, WIDTH, y);

            // Historical celestial treatment: translucent halo + solid body disc.
            int index = 0;
            for (CelestialBodyDefinition body : definition.bodies()) {
                int px = 170 + (index % 5) * 220;
                int py = 120 + (index / 5) * 230;
                double radius = Math.max(12, Math.min(65, body.radius() * 0.28));
                Color c = body.color();
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), body.parentId() == null ? 80 : 35));
                g.fillOval((int)(px-radius*2.2), (int)(py-radius*2.2), (int)(radius*4.4), (int)(radius*4.4));
                g.setColor(c);
                g.fillOval((int)(px-radius), (int)(py-radius), (int)(radius*2), (int)(radius*2));
                index++;
            }
        }

        private void drawCurrentGas(Graphics2D g) {
            for (ResourceNode node : gas) node.draw(g, false);
        }

        private void drawLegacyGas(Graphics2D g) {
            for (ResourceNode node : gas) {
                double r = node.radius;
                Color c = node.material.color;
                for (int i = 0; i < 7; i++) {
                    double a = i * Math.PI * 2 / 7.0;
                    double ox = Math.cos(a) * r * .25, oy = Math.sin(a) * r * .22;
                    g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 42 + i * 8));
                    g.fillOval((int)(node.x + ox-r*.55), (int)(node.y + oy-r*.42), (int)(r*1.1), (int)(r*.84));
                }
            }
        }

        private void drawCurrentShips(Graphics2D g) {
            for (int i = 0; i < ships.size(); i++) {
                ShipType type = ships.get(i);
                Graphics2D ship = (Graphics2D)g.create();
                ship.translate(175 + i * 225, 565);
                ship.rotate(-0.35 + i * 0.17);
                ShowcaseShipRenderer.draw(ship, type, new Color(85, 185, 245));
                ShipLighting.draw(ship, type);
                ship.dispose();
            }
        }

        private void drawLegacyShips(Graphics2D g) {
            for (int i = 0; i < ships.size(); i++) {
                ShipType type = ships.get(i);
                Graphics2D ship = (Graphics2D)g.create();
                ship.translate(175 + i * 225, 565);
                ship.rotate(-0.35 + i * 0.17);
                ShipShape.draw(ship, type, new Color(85, 185, 245));
                ship.dispose();
            }
        }

        private void drawCurrentStations(Graphics2D g) {
            for (int i = 0; i < stations.size(); i++) {
                Base base = new Base(stations.get(i).id, "SOLO", stations.get(i).typeId, 260 + i * 375, 360);
                base.draw(g, Color.CYAN, base.inventory, true);
            }
        }

        private void drawLegacyStations(Graphics2D g) {
            for (int i = 0; i < stations.size(); i++) {
                int cx = 260 + i * 375;
                int cy = 360;
                int radius = stations.get(i).typeId.equals("shipyard") ? 82 : stations.get(i).typeId.equals("manufacturing") ? 74 : 64;
                Polygon hull = new Polygon();
                for (int p = 0; p < 6; p++) {
                    double a = Math.PI / 6 + p * Math.PI * 2 / 6.0;
                    hull.addPoint((int)Math.round(cx + Math.cos(a) * radius), (int)Math.round(cy + Math.sin(a) * radius));
                }
                g.setColor(new Color(20, 29, 42));
                g.fillPolygon(hull);
                g.setColor(new Color(85, 185, 245));
                g.setStroke(new BasicStroke(3f));
                g.drawPolygon(hull);
            }
        }

        private List<ResourceNode> buildGas() {
            Random random = new Random(4070);
            java.util.ArrayList<ResourceNode> nodes = new java.util.ArrayList<>();
            Material[] materials = {Material.HYDROGEN, Material.HELIUM, Material.METHANE, Material.AMMONIA};
            for (int i = 0; i < 72; i++) {
                Material material = materials[i % materials.length];
                double x = 35 + random.nextDouble() * (WIDTH - 70);
                double y = 35 + random.nextDouble() * 245;
                nodes.add(new ResourceNode(70_000 + i, "Benchmark Gas", NodeKind.GAS_CLOUD, material,
                        x, y, 100, 8, 14 + random.nextDouble() * 14));
            }
            return List.copyOf(nodes);
        }
    }

    private record Metrics(double meanMs, double p95Ms) {
        static Metrics of(long[] nanos) {
            long[] sorted = nanos.clone();
            Arrays.sort(sorted);
            double mean = Arrays.stream(sorted).average().orElse(0) / 1_000_000.0;
            int p95Index = Math.min(sorted.length - 1, (int)Math.ceil(sorted.length * 0.95) - 1);
            return new Metrics(mean, sorted[p95Index] / 1_000_000.0);
        }
    }
}
