package com.tndmadman.rts;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Headless production-render acceptance benchmark for #407.
 *
 * The benchmark intentionally exercises SpaceBackgroundRenderer itself for every
 * authored system and the integrated Nebula Expanse world stack. It does not use
 * a synthetic or legacy stand-in renderer for acceptance measurements.
 */
final class Issue407GraphicsBenchmark {
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int BACKGROUND_WARMUP = 5;
    private static final int BACKGROUND_SAMPLES = 15;
    private static final int SLICE_WARMUP = 8;
    private static final int SLICE_SAMPLES = 24;
    private static final double BACKGROUND_P95_LIMIT_MS = 250.0;
    private static final double SLICE_P95_LIMIT_MS = 500.0;
    private static final double[] GAMEPLAY_ZOOMS = {0.36, 0.52, 0.712};
    private static final String[] SHIPS = {"prospector", "frigate", "cruiser", "battleship", "titan"};
    private static final String[] STATIONS = {"outpost", "shipyard", "manufacturing"};

    private Issue407GraphicsBenchmark() { }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        List<StarSystemDefinition> systems = StarSystems.options();
        require(!systems.isEmpty(), "No predefined systems are available");
        benchmarkAllProductionBackgrounds(systems);
        benchmarkNebulaVerticalSlice();
    }

    private static void benchmarkAllProductionBackgrounds(List<StarSystemDefinition> systems) {
        for (StarSystemDefinition definition : systems) {
            BackgroundBench bench = new BackgroundBench(definition);
            long seed = bench.seed();
            long repeatSeed = new SpaceBackgroundRenderer(definition).seedForTest();
            require(seed == repeatSeed, "Background seed is not deterministic for " + definition.id());

            for (int i = 0; i < BACKGROUND_WARMUP; i++) {
                bench.render(GAMEPLAY_ZOOMS[i % GAMEPLAY_ZOOMS.length]);
            }
            long[] samples = new long[BACKGROUND_SAMPLES];
            for (int i = 0; i < samples.length; i++) {
                double zoom = GAMEPLAY_ZOOMS[i % GAMEPLAY_ZOOMS.length];
                samples[i] = timed(() -> bench.render(zoom));
            }
            Metrics metrics = Metrics.of(samples);
            System.out.printf(
                    "ISSUE407_BACKGROUND system=%s seed=%d dimensions=%dx%d warmup=%d samples=%d p50_ms=%.3f p95_ms=%.3f threshold_p95_ms=%.3f%n",
                    definition.id(), seed, WIDTH, HEIGHT, BACKGROUND_WARMUP, BACKGROUND_SAMPLES,
                    metrics.p50Ms(), metrics.p95Ms(), BACKGROUND_P95_LIMIT_MS);
            require(metrics.p95Ms() <= BACKGROUND_P95_LIMIT_MS,
                    "Production background p95 exceeded threshold for " + definition.id()
                            + ": seed=" + seed + " dimensions=" + WIDTH + "x" + HEIGHT
                            + " p50=" + format(metrics.p50Ms()) + "ms p95=" + format(metrics.p95Ms())
                            + "ms threshold=" + format(BACKGROUND_P95_LIMIT_MS) + "ms");
        }
    }

    private static void benchmarkNebulaVerticalSlice() {
        StarSystemDefinition definition = StarSystems.get("nebula_expanse");
        require(definition != null && "nebula_expanse".equals(definition.id()), "Nebula Expanse is unavailable");
        for (String id : SHIPS) require(ShipVisualCatalog.forType(Rules.ship(id)) != null, "Missing ship visual: " + id);
        for (String id : STATIONS) require(Rules.base(id) != null, "Missing station: " + id);

        PlayerRegistry.reset("SOLO", "Nebula Expanse Benchmark", 0x55B9F5);
        Scene scene = new Scene(definition);
        require("NEBULA".equals(scene.backgroundTheme()), "Nebula Expanse did not use the production nebula background theme");
        require(scene.seed() == new SpaceBackgroundRenderer(definition).seedForTest(), "Nebula background seed is not deterministic");

        for (int i = 0; i < SLICE_WARMUP; i++) {
            scene.render(GAMEPLAY_ZOOMS[i % GAMEPLAY_ZOOMS.length]);
        }
        long[] samples = new long[SLICE_SAMPLES];
        for (int i = 0; i < samples.length; i++) {
            double zoom = GAMEPLAY_ZOOMS[i % GAMEPLAY_ZOOMS.length];
            samples[i] = timed(() -> scene.render(zoom));
        }

        long visible = scene.currentVisiblePixels();
        require(visible > WIDTH * HEIGHT / 20L, "Integrated Nebula slice rendered too little visual content");
        require(scene.zoomHashesAreDistinct(), "Nebula production stack did not respond across gameplay zoom levels");
        Metrics metrics = Metrics.of(samples);
        System.out.printf(
                "ISSUE407_NEBULA system=%s seed=%d dimensions=%dx%d warmup=%d samples=%d p50_ms=%.3f p95_ms=%.3f threshold_p95_ms=%.3f visible_pixels=%d zooms=%s background=%s%n",
                definition.id(), scene.seed(), WIDTH, HEIGHT, SLICE_WARMUP, SLICE_SAMPLES,
                metrics.p50Ms(), metrics.p95Ms(), SLICE_P95_LIMIT_MS, visible,
                Arrays.toString(GAMEPLAY_ZOOMS), scene.backgroundTheme());
        require(metrics.p95Ms() <= SLICE_P95_LIMIT_MS,
                "Integrated Nebula Expanse p95 exceeded threshold: seed=" + scene.seed()
                        + " dimensions=" + WIDTH + "x" + HEIGHT
                        + " p50=" + format(metrics.p50Ms()) + "ms p95=" + format(metrics.p95Ms())
                        + "ms threshold=" + format(SLICE_P95_LIMIT_MS) + "ms");
    }

    private static long timed(Runnable runnable) {
        long start = System.nanoTime();
        runnable.run();
        return System.nanoTime() - start;
    }

    private static final class BackgroundBench {
        private final BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        private final StarSystemDefinition definition;
        private final SpaceBackgroundRenderer renderer;
        private final double centerX;
        private final double centerY;

        BackgroundBench(StarSystemDefinition definition) {
            this.definition = definition;
            renderer = new SpaceBackgroundRenderer(definition);
            centerX = definition.width() * 0.5;
            centerY = definition.height() * 0.5;
        }

        long seed() { return renderer.seedForTest(); }

        void render(double zoom) {
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(5, 8, 14));
            g.fillRect(0, 0, WIDTH, HEIGHT);
            Graphics2D world = worldFrame(g, centerX, centerY, zoom);
            renderer.draw(world);
            world.dispose();
            g.dispose();
        }
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
            background = new SpaceBackgroundRenderer(definition);
            celestial = new CelestialSystem(definition, new Random(407));
            centerX = definition.width() * 0.5;
            centerY = definition.height() * 0.5;

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
        long seed() { return background.seedForTest(); }

        void render(double zoom) {
            Graphics2D g = beginFrame();
            Graphics2D world = worldFrame(g, centerX, centerY, zoom);
            background.draw(world);
            celestial.draw(world);
            for (ResourceNode node : gas) node.draw(world, false);
            for (Unit unit : ships) UnitRenderer.draw(world, unit, Color.CYAN, true);
            for (Base base : stations) base.draw(world, Color.CYAN, base.inventory, true);
            world.dispose();
            g.dispose();
        }

        boolean zoomHashesAreDistinct() {
            long first = Long.MIN_VALUE;
            for (double zoom : GAMEPLAY_ZOOMS) {
                render(zoom);
                long hash = imageHash(image);
                if (first == Long.MIN_VALUE) first = hash;
                else if (hash == first) return false;
            }
            return true;
        }

        long currentVisiblePixels() {
            render(GAMEPLAY_ZOOMS[1]);
            int backgroundRgb = new Color(5, 8, 14).getRGB();
            long count = 0;
            for (int y = 0; y < HEIGHT; y += 2) {
                for (int x = 0; x < WIDTH; x += 2) {
                    if (image.getRGB(x, y) != backgroundRgb) count += 4;
                }
            }
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

    private static Graphics2D worldFrame(Graphics2D source, double centerX, double centerY, double zoom) {
        Graphics2D world = (Graphics2D)source.create();
        world.scale(zoom, zoom);
        world.translate(-centerX + WIDTH / (2.0 * zoom), -centerY + HEIGHT / (2.0 * zoom));
        return world;
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

    private record Metrics(double p50Ms, double p95Ms) {
        static Metrics of(long[] values) {
            require(values != null && values.length > 0, "Benchmark produced no measured samples");
            long[] sorted = values.clone();
            Arrays.sort(sorted);
            return new Metrics(percentile(sorted, 0.50) / 1_000_000.0,
                    percentile(sorted, 0.95) / 1_000_000.0);
        }

        private static long percentile(long[] sorted, double p) {
            int index = Math.min(sorted.length - 1, Math.max(0, (int)Math.ceil(sorted.length * p) - 1));
            return sorted[index];
        }
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
