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
    private static final double[] GAMEPLAY_ZOOMS = {0.36, 0.52, 0.712};
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
        require("NEBULA".equals(scene.backgroundTheme()), "Nebula Expanse did not use the production nebula background theme");
        for (int i = 0; i < WARMUP; i++) {
            double zoom = GAMEPLAY_ZOOMS[i % GAMEPLAY_ZOOMS.length];
            scene.renderLegacy(zoom);
            scene.renderCurrent(zoom);
        }

        long[] legacy = new long[SAMPLES];
        long[] current = new long[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            double zoom = GAMEPLAY_ZOOMS[i % GAMEPLAY_ZOOMS.length];
            if ((i & 1) == 0) {
                legacy[i] = timed(() -> scene.renderLegacy(zoom));
                current[i] = timed(() -> scene.renderCurrent(zoom));
            } else {
                current[i] = timed(() -> scene.renderCurrent(zoom));
                legacy[i] = timed(() -> scene.renderLegacy(zoom));
            }
        }

        long visible = scene.currentVisiblePixels();
        require(visible > WIDTH * HEIGHT / 20L, "Integrated Nebula slice rendered too little visual content");
        require(scene.zoomHashesAreDistinct(), "Nebula production stack did not respond across gameplay zoom levels");
        Metrics before = Metrics.of(legacy);
        Metrics after = Metrics.of(current);
        double ratio = before.meanMs <= 0 ? 0 : after.meanMs / before.meanMs;
        System.out.printf("ISSUE407_GRAPHICS legacy_mean_ms=%.3f legacy_p95_ms=%.3f current_mean_ms=%.3f current_p95_ms=%.3f ratio=%.3f visible_pixels=%d samples=%d zooms=%s background=%s%n",
                before.meanMs, before.p95Ms, after.meanMs, after.p95Ms, ratio, visible, SAMPLES,
                Arrays.toString(GAMEPLAY_ZOOMS), scene.backgroundTheme());
    }

    private static long timed(Runnable runnable) {
        long start = System.nanoTime();
        runnable.run();
        return System.nanoTime() - start;
    }

    private static final class Scene {
        private final BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        private final StarSystemDefinition definition;
        private final SpaceBackgroundRenderer background;
        private final CelestialSystem celestial;
        private final List<ResourceNode> gas;
        private final List<Unit> ships;
        private final List<Base> stations;
        private final double centerX;
        private final double centerY;

        Scene(StarSystemDefinition definition) {
            this.definition = definition;
            this.background = new SpaceBackgroundRenderer(definition);
            this.celestial = new CelestialSystem(definition, new Random(407));
            this.centerX = definition.width() * 0.5;
            this.centerY = definition.height() * 0.5;

            ArrayList<Unit> units = new ArrayList<>(SHIPS.length);
            for (int i = 0; i < SHIPS.length; i++) {
                Unit unit = new Unit("SOLO", 407_000 + i, SHIPS[i], centerX - 900 + i * 450, centerY + 430);
                unit.heading = -0.35 + i * 0.17;
                unit.afterburnerActive = (i & 1) == 0;
                units.add(unit);
            }
            ships = List.copyOf(units);

            ArrayList<Base> bases = new ArrayList<>(STATIONS.length);
            for (int i = 0; i < STATIONS.length; i++) {
                bases.add(new Base("issue407-" + STATIONS[i], "SOLO", STATIONS[i],
                        centerX - 720 + i * 720, centerY - 180));
            }
            stations = List.copyOf(bases);

            Random random = new Random(4070);
            Material[] materials = {Material.HYDROGEN, Material.HELIUM, Material.METHANE, Material.AMMONIA};
            ArrayList<ResourceNode> nodes = new ArrayList<>();
            for (int i = 0; i < 72; i++) {
                Material material = materials[i % materials.length];
                nodes.add(new ResourceNode(70_000 + i, "Nebula Gas", NodeKind.GAS_CLOUD, material,
                        centerX - 1500 + random.nextDouble() * 3000,
                        centerY - 850 + random.nextDouble() * 650,
                        100, 8, 14 + random.nextDouble() * 14));
            }
            gas = List.copyOf(nodes);
        }

        String backgroundTheme() { return background.themeNameForTest(); }

        void renderCurrent(double zoom) {
            Graphics2D g = beginFrame();
            Graphics2D world = worldFrame(g, zoom);

            // This is deliberately the normal production order: system-specific background first,
            // followed by celestial/resource/ship/station world content under the same camera transform.
            background.draw(world);
            celestial.draw(world);
            for (ResourceNode node : gas) node.draw(world, false);
            for (Unit unit : ships) UnitRenderer.draw(world, unit, Color.CYAN, true);
            for (Base base : stations) base.draw(world, Color.CYAN, base.inventory, true);

            world.dispose();
            g.dispose();
        }

        void renderLegacy(double zoom) {
            Graphics2D g = beginFrame();
            Graphics2D world = worldFrame(g, zoom);
            double visibleW = WIDTH / zoom;
            double visibleH = HEIGHT / zoom;
            int left = (int)Math.floor(centerX - visibleW * 0.5);
            int top = (int)Math.floor(centerY - visibleH * 0.5);
            int right = (int)Math.ceil(centerX + visibleW * 0.5);
            int bottom = (int)Math.ceil(centerY + visibleH * 0.5);
            world.setColor(new Color(38, 52, 68, 90));
            world.setStroke(new BasicStroke((float)Math.max(1.0, 1.0 / zoom)));
            for (int x = left - Math.floorMod(left, 160); x <= right; x += 160) world.drawLine(x, top, x, bottom);
            for (int y = top - Math.floorMod(top, 160); y <= bottom; y += 160) world.drawLine(left, y, right, y);
            for (Unit unit : ships) {
                int cx = (int)Math.round(unit.x), cy = (int)Math.round(unit.y), r = 20;
                Polygon p = new Polygon(new int[]{cx-r, cx+r, cx-r/2}, new int[]{cy, cy-r, cy+r}, 3);
                world.setColor(new Color(45, 90, 120)); world.fillPolygon(p);
                world.setColor(new Color(85, 185, 245)); world.drawPolygon(p);
            }
            for (Base base : stations) {
                int cx = (int)Math.round(base.x), cy = (int)Math.round(base.y), r = 60;
                Polygon p = new Polygon();
                for (int n = 0; n < 6; n++) {
                    double a = Math.PI / 6 + n * Math.PI * 2 / 6.0;
                    p.addPoint((int)Math.round(cx + Math.cos(a) * r), (int)Math.round(cy + Math.sin(a) * r));
                }
                world.setColor(new Color(20, 29, 42)); world.fillPolygon(p);
                world.setColor(new Color(85, 185, 245)); world.drawPolygon(p);
            }
            world.dispose();
            g.dispose();
        }

        boolean zoomHashesAreDistinct() {
            long first = Long.MIN_VALUE;
            for (double zoom : GAMEPLAY_ZOOMS) {
                renderCurrent(zoom);
                long hash = imageHash(image);
                if (first == Long.MIN_VALUE) first = hash;
                else if (hash == first) return false;
            }
            return true;
        }

        long currentVisiblePixels() {
            renderCurrent(GAMEPLAY_ZOOMS[1]);
            int backgroundRgb = new Color(5, 8, 14).getRGB();
            long count = 0;
            for (int y = 0; y < HEIGHT; y += 2) for (int x = 0; x < WIDTH; x += 2)
                if (image.getRGB(x, y) != backgroundRgb) count += 4;
            return count;
        }

        private Graphics2D worldFrame(Graphics2D source, double zoom) {
            Graphics2D world = (Graphics2D)source.create();
            world.scale(zoom, zoom);
            world.translate(-centerX + WIDTH / (2.0 * zoom), -centerY + HEIGHT / (2.0 * zoom));
            return world;
        }

        private Graphics2D beginFrame() {
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(5, 8, 14));
            g.fillRect(0, 0, WIDTH, HEIGHT);
            return g;
        }
    }

    private static long imageHash(BufferedImage image) {
        long hash = 0xcbf29ce484222325L;
        for (int y = 0; y < image.getHeight(); y += 2) {
            for (int x = 0; x < image.getWidth(); x += 2) {
                hash ^= image.getRGB(x, y);
                hash *= 0x100000001b3L;
            }
        }
        return hash;
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
