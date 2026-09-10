package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Headless acceptance/relative benchmark for the fully integrated #407 Nebula Expanse slice. */
final class Issue407GraphicsBenchmark {
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int WARMUP = 12;
    private static final int SAMPLES = 40;
    private static final String[] SHIPS = {"prospector", "frigate", "cruiser", "battleship", "titan"};
    private static final String[] STATIONS = {"outpost", "shipyard", "manufacturing"};

    private Issue407GraphicsBenchmark() { }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        StarSystemDefinition definition = StarSystems.get("nebula_expanse");
        require(definition != null && "nebula_expanse".equals(definition.id()), "Nebula Expanse is unavailable");
        for (String id : SHIPS) require(ShipVisualCatalog.forType(Rules.ship(id)) != null, "Missing ship visual: " + id);
        for (String id : STATIONS) require(Rules.base(id) != null, "Missing station: " + id);

        PlayerRegistry.reset("SOLO", "Nebula Expanse Benchmark", 0x55B9F5);
        Scene scene = new Scene(definition);
        for (int i = 0; i < WARMUP; i++) { scene.renderLegacy(); scene.renderCurrent(); }

        long[] legacy = new long[SAMPLES];
        long[] current = new long[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            if ((i & 1) == 0) {
                legacy[i] = timed(scene::renderLegacy);
                current[i] = timed(scene::renderCurrent);
            } else {
                current[i] = timed(scene::renderCurrent);
                legacy[i] = timed(scene::renderLegacy);
            }
        }

        long visible = scene.currentVisiblePixels();
        require(visible > WIDTH * HEIGHT / 20L, "Integrated Nebula slice rendered too little visual content");
        Metrics before = Metrics.of(legacy);
        Metrics after = Metrics.of(current);
        double ratio = before.meanMs <= 0 ? 0 : after.meanMs / before.meanMs;
        System.out.printf("ISSUE407_GRAPHICS legacy_mean_ms=%.3f legacy_p95_ms=%.3f current_mean_ms=%.3f current_p95_ms=%.3f ratio=%.3f visible_pixels=%d samples=%d%n",
                before.meanMs, before.p95Ms, after.meanMs, after.p95Ms, ratio, visible, SAMPLES);
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
        private final List<Unit> ships;

        Scene(StarSystemDefinition definition) {
            this.definition = definition;
            celestial = new CelestialSystem(definition, new Random(407));
            ArrayList<Unit> units = new ArrayList<>(SHIPS.length);
            for (int i = 0; i < SHIPS.length; i++) {
                Unit unit = new Unit("SOLO", 407_000 + i, SHIPS[i], 175 + i * 225, 565);
                unit.heading = -0.35 + i * 0.17;
                unit.afterburnerActive = (i & 1) == 0;
                units.add(unit);
            }
            ships = List.copyOf(units);

            Random random = new Random(4070);
            Material[] materials = {Material.HYDROGEN, Material.HELIUM, Material.METHANE, Material.AMMONIA};
            java.util.ArrayList<ResourceNode> nodes = new java.util.ArrayList<>();
            for (int i = 0; i < 72; i++) {
                Material material = materials[i % materials.length];
                nodes.add(new ResourceNode(70_000 + i, "Nebula Gas", NodeKind.GAS_CLOUD, material,
                        40 + random.nextDouble() * (WIDTH - 80), 35 + random.nextDouble() * 235,
                        100, 8, 14 + random.nextDouble() * 14));
            }
            gas = List.copyOf(nodes);
        }

        void renderCurrent() {
            Graphics2D g = beginFrame();
            Graphics2D world = (Graphics2D) g.create();
            double scale = Math.min(WIDTH / (double) definition.width(), HEIGHT / (double) definition.height()) * 0.94;
            double visibleW = WIDTH / scale;
            double visibleH = HEIGHT / scale;
            world.scale(scale, scale);
            world.translate((visibleW - definition.width()) * 0.5, (visibleH - definition.height()) * 0.5);
            celestial.draw(world);
            world.dispose();

            // Exercise the same production entry points used during normal play. The benchmark owns
            // only scene setup and the legacy comparison; it does not maintain a showcase renderer.
            for (ResourceNode node : gas) node.draw(g, false);
            for (Unit unit : ships) UnitRenderer.draw(g, unit, Color.CYAN, true);
            for (int i = 0; i < STATIONS.length; i++) {
                Base base = new Base("issue407-" + STATIONS[i], "SOLO", STATIONS[i], 260 + i * 375, 360);
                base.draw(g, Color.CYAN, base.inventory, true);
            }
            g.dispose();
        }

        void renderLegacy() {
            Graphics2D g = beginFrame();
            g.setColor(new Color(38, 52, 68, 90));
            g.setStroke(new BasicStroke(1f));
            for (int x = 0; x < WIDTH; x += 80) g.drawLine(x, 0, x, HEIGHT);
            for (int y = 0; y < HEIGHT; y += 80) g.drawLine(0, y, WIDTH, y);
            for (int i = 0; i < ships.size(); i++) {
                int cx = 175 + i * 225, cy = 565, r = 16 + i * 3;
                Polygon p = new Polygon(new int[]{cx-r, cx+r, cx-r/2}, new int[]{cy, cy-r, cy+r}, 3);
                g.setColor(new Color(45, 90, 120)); g.fillPolygon(p);
                g.setColor(new Color(85, 185, 245)); g.drawPolygon(p);
            }
            for (int i = 0; i < STATIONS.length; i++) {
                int cx = 260 + i * 375, cy = 360, r = 55 + i * 9;
                Polygon p = new Polygon();
                for (int n = 0; n < 6; n++) {
                    double a = Math.PI / 6 + n * Math.PI * 2 / 6.0;
                    p.addPoint((int)Math.round(cx + Math.cos(a) * r), (int)Math.round(cy + Math.sin(a) * r));
                }
                g.setColor(new Color(20, 29, 42)); g.fillPolygon(p);
                g.setColor(new Color(85, 185, 245)); g.drawPolygon(p);
            }
            g.dispose();
        }

        long currentVisiblePixels() {
            renderCurrent();
            int background = new Color(5, 8, 14).getRGB();
            long count = 0;
            for (int y = 0; y < HEIGHT; y += 2) for (int x = 0; x < WIDTH; x += 2)
                if (image.getRGB(x, y) != background) count += 4;
            return count;
        }

        private Graphics2D beginFrame() {
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(5, 8, 14));
            g.fillRect(0, 0, WIDTH, HEIGHT);
            return g;
        }
    }

    private record Metrics(double meanMs, double p95Ms) {
        static Metrics of(long[] values) {
            long[] sorted = values.clone(); Arrays.sort(sorted);
            double mean = Arrays.stream(sorted).average().orElse(0) / 1_000_000.0;
            int p95 = Math.min(sorted.length - 1, Math.max(0, (int)Math.ceil(sorted.length * 0.95) - 1));
            return new Metrics(mean, sorted[p95] / 1_000_000.0);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
