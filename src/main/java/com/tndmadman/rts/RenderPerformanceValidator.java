package com.tndmadman.rts;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Repeatable, headless production-render stress harness for issue #408. */
public final class RenderPerformanceValidator {
    private static final int IMAGE_WIDTH = 1600;
    private static final int IMAGE_HEIGHT = 900;
    private static final int WARMUP_FRAMES = 4;
    private static final int SAMPLE_FRAMES = 25;
    private static final double[] ZOOMS = {0.45, 1.0, 1.75};
    private static final String NEBULA_EXPANSE = "nebula_expanse";
    private static final String[] LARGE_STATION_TYPES = {"shipyard", "manufacturing", "laboratory"};
    private static final double DEFAULT_MAX_REGRESSION_PERCENT = 20.0;
    private static final int MAX_PARTICLE_BUDGET_PER_EFFECT = 240;
    private static final int MAX_DEBRIS_PER_EFFECT = 28;
    private static final int MAX_VENTS_PER_EFFECT = 8;

    private RenderPerformanceValidator() { }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Options options = Options.parse(args);

        validateDestructionBudgets();
        validateSpriteCacheBound();

        List<Scenario> scenarios = scenarios();
        validateScenarioCoverage(scenarios);
        List<Result> results = new ArrayList<>();
        Map<String, Double> backgroundMedianBySystemAndZoom = new HashMap<>();

        for (Scenario scenario : scenarios) {
            for (double zoom : ZOOMS) {
                Result result = measure(scenario, zoom);
                results.add(result);
                if (scenario.backgroundOnly()) {
                    backgroundMedianBySystemAndZoom.put(backgroundKey(scenario.systemId, zoom), result.p50Ms);
                }
            }
        }

        List<Result> enriched = new ArrayList<>(results.size());
        for (Result result : results) {
            double background = backgroundMedianBySystemAndZoom.getOrDefault(
                    backgroundKey(result.scenario.systemId, result.zoom), 0.0);
            enriched.add(result.withIncrementalMs(Math.max(0.0, result.p50Ms - background)));
        }

        printResults(enriched);
        printAttributedCosts(enriched);
        if (options.output != null) writeCsv(options.output, enriched);
        if (options.baseline != null) compareBaseline(options.baseline, enriched, options.maxRegressionPercent);
        if (options.enforceTiming) enforceTimingBudgets(enriched);

        System.out.printf(Locale.ROOT,
                "Render performance validator passed. Timing gate: %s. Max baseline regression: %.1f%%%n",
                options.enforceTiming ? "ENFORCED" : "report-only", options.maxRegressionPercent);
    }

    private static List<Scenario> scenarios() {
        String defaultSystem = StarSystems.DEFAULT_SYSTEM_ID;
        return List.of(
                new Scenario("background-default", defaultSystem, 0, false, 0, 0, 0, 0, 6.0),
                new Scenario("fleet-20", defaultSystem, 20, false, 0, 0, 0, 0, 12.0),
                new Scenario("fleet-50", defaultSystem, 50, false, 0, 0, 0, 0, 16.67),
                new Scenario("fleet-100", defaultSystem, 100, false, 0, 0, 0, 0, 25.0),
                new Scenario("fleet-150", defaultSystem, 150, false, 0, 0, 0, 0, 30.0),
                new Scenario("fleet-100-selected", defaultSystem, 100, true, 0, 0, 0, 0, 28.0),
                new Scenario("multi-large-station", defaultSystem, 0, false, 9, 0, 0, 0, 20.0),
                new Scenario("resource-field", defaultSystem, 0, false, 0, 180, 0, 0, 22.0),
                new Scenario("heavy-combat", defaultSystem, 80, false, 0, 0, 220, 56, 33.33),
                new Scenario("capital-station-destruction", defaultSystem, 20, false, 6, 0, 60, 96, 33.33),
                new Scenario("background-nebula-expanse", NEBULA_EXPANSE, 0, false, 0, 0, 0, 0, 12.0));
    }

    private static Result measure(Scenario scenario, double zoom) {
        World world = buildWorld(scenario);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("P1", "RenderPerf", 0x50BEFF);
        ShipSpriteCache.resetForTest();

        BufferedImage image = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g2 = image.createGraphics();
        try {
            configureGraphics(g2, world, zoom);
            long coldStarted = System.nanoTime();
            world.draw(g2);
            double coldMs = nanosToMs(System.nanoTime() - coldStarted);

            for (int i = 0; i < WARMUP_FRAMES; i++) world.draw(g2);

            long[] samples = new long[SAMPLE_FRAMES];
            for (int i = 0; i < samples.length; i++) {
                long started = System.nanoTime();
                world.draw(g2);
                samples[i] = System.nanoTime() - started;
            }
            Arrays.sort(samples);

            ShipSpriteCache.Snapshot cache = ShipSpriteCache.snapshot();
            require(cache.entries() <= cache.maxEntries(),
                    "Ship sprite cache exceeded hard limit: " + cache.entries() + " > " + cache.maxEntries());
            require(cache.requests() == cache.hits() + cache.misses(),
                    "Ship sprite cache request accounting is inconsistent.");
            require(cache.generations() == cache.misses(),
                    "Ship sprite cache generation accounting is inconsistent.");
            require(world.explosions.size() <= ExplosionEffect.maxActiveEffectsForTest(),
                    "Destruction VFX exceeded the production admission cap.");

            return new Result(
                    scenario,
                    zoom,
                    coldMs,
                    nanosToMs(samples[samples.length / 2]),
                    nanosToMs(samples[p95Index(samples.length)]),
                    nanosToMs(samples[samples.length - 1]),
                    0.0,
                    cache.hitRate(),
                    cache.requests(),
                    cache.hits(),
                    cache.misses(),
                    cache.generations(),
                    cache.entries(),
                    cache.peakEntries(),
                    cache.evictions(),
                    cache.generationMs(),
                    cache.averageGenerationMs(),
                    cache.estimatedBytes());
        } finally {
            releaseExplosions(world);
            g2.dispose();
        }
    }

    private static World buildWorld(Scenario scenario) {
        World world = new World("Render performance validator", Set.of(), scenario.systemId, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("P1", "RenderPerf", 0x50BEFF);
        world.units.clear();
        world.bases.clear();
        world.resources.clear();
        world.shots.clear();
        world.explosions.clear();
        world.items.clear();
        world.wormholes.clear();

        double cx = world.width / 2.0;
        double cy = world.height / 2.0;

        for (int i = 0; i < scenario.stations; i++) {
            double angle = i * Math.PI * 2.0 / Math.max(1, scenario.stations);
            double x = cx + Math.cos(angle) * 430;
            double y = cy + Math.sin(angle) * 300;
            String id = "P1:B" + (i + 1);
            String typeId = largeStationType(i);
            world.bases.put(id, new Base(id, "P1", typeId, x, y));
        }

        String shipTypeId = shipTypeForScenario(scenario);
        int cols = Math.max(1, (int)Math.ceil(Math.sqrt(Math.max(1, scenario.ships))));
        for (int i = 0; i < scenario.ships; i++) {
            int row = i / cols;
            int col = i % cols;
            double x = cx + (col - (cols - 1) / 2.0) * 52;
            double y = cy + (row - (cols - 1) / 2.0) * 52;
            Unit unit = new Unit("P1", i + 1, shipTypeId, x, y);
            unit.heading = (i % 48) * Math.PI * 2.0 / 48.0;
            unit.selected = scenario.selected;
            world.units.put(unit.key(), unit);
        }

        Material[] materials = Material.values();
        for (int i = 0; i < scenario.resources; i++) {
            int col = i % 18;
            int row = i / 18;
            double x = cx + (col - 8.5) * 92;
            double y = cy + (row - 4.5) * 76;
            Material material = materials[i % materials.length];
            NodeKind kind = i % 2 == 0 ? NodeKind.SILICATE_ROCK : NodeKind.GAS_CLOUD;
            world.resources.add(new ResourceNode(
                    i + 1, "Perf field " + (i + 1), kind, material,
                    x, y, 5000, 18, 28 + (i % 4) * 5));
        }

        if (scenario.shots > 0 && !WeaponRules.WEAPONS.isEmpty()) {
            String weaponId = WeaponRules.WEAPONS.keySet().iterator().next();
            for (int i = 0; i < scenario.shots; i++) {
                double angle = i * 0.47;
                double radius = 60 + (i % 12) * 32;
                double x = cx + Math.cos(angle) * radius;
                double y = cy + Math.sin(angle) * radius;
                ProjectileShot shot = new ProjectileShot(i + 1, "P1", weaponId, "", x, y);
                shot.lastX = x - Math.cos(angle) * 24;
                shot.lastY = y - Math.sin(angle) * 24;
                world.shots.add(shot);
            }
        }

        populateProductionDestruction(world, scenario);
        return world;
    }

    /**
     * Use the production #405 constructors so stress VFX are positioned on the actual visible ships/stations,
     * inherit the real size/profile budgets, and exercise the same admission/culling/LOD path as gameplay.
     * Geometry is fixed; the production effect seed may vary, but configured work counts remain bounded.
     */
    private static void populateProductionDestruction(World world, Scenario scenario) {
        if (scenario.explosions <= 0) return;
        List<Unit> units = new ArrayList<>(world.units.values());
        List<Base> bases = new ArrayList<>(world.bases.values());
        require(!units.isEmpty() || !bases.isEmpty(),
                "A destruction stress scene must contain a production entity to explode.");

        for (int i = 0; i < scenario.explosions; i++) {
            ExplosionEffect effect;
            if (scenario.name.equals("capital-station-destruction") && i % 4 == 0 && !bases.isEmpty()) {
                effect = ExplosionEffect.fromBase(bases.get(i % bases.size()));
            } else if (!units.isEmpty()) {
                effect = ExplosionEffect.fromUnit(units.get(i % units.size()));
            } else {
                effect = ExplosionEffect.fromBase(bases.get(i % bases.size()));
            }
            world.explosions.add(effect);

            double targetAge = scenario.name.equals("capital-station-destruction")
                    ? (i % 18) * 0.25
                    : (i % 6) * 0.20;
            advanceEffect(effect, targetAge);
            require(world.explosions.size() <= ExplosionEffect.maxActiveEffectsForTest(),
                    "Production destruction admission exceeded its cap while constructing stress scene.");
        }
    }

    private static void advanceEffect(ExplosionEffect effect, double seconds) {
        double remaining = Math.max(0.0, seconds);
        while (remaining > 0.000001) {
            double dt = Math.min(0.25, remaining);
            require(effect.update(dt), "Stress effect expired before its intended staged render point.");
            remaining -= dt;
        }
    }

    private static String largeStationType(int index) {
        String candidate = LARGE_STATION_TYPES[Math.floorMod(index, LARGE_STATION_TYPES.length)];
        return Rules.BASES.containsKey(candidate) ? candidate : Rules.DEFAULT_BASE;
    }

    private static String shipTypeForScenario(Scenario scenario) {
        if (scenario.name.equals("capital-station-destruction") && Rules.SHIPS.containsKey("titan")) return "titan";
        if (scenario.name.equals("heavy-combat") && Rules.SHIPS.containsKey("battleship")) return "battleship";
        return Rules.STARTING_SHIP;
    }

    private static void configureGraphics(Graphics2D g2, World world, double zoom) {
        g2.setTransform(new AffineTransform());
        g2.setClip(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.translate(IMAGE_WIDTH / 2.0, IMAGE_HEIGHT / 2.0);
        g2.scale(zoom, zoom);
        g2.translate(-world.width / 2.0, -world.height / 2.0);
    }

    private static void validateScenarioCoverage(List<Scenario> scenarios) {
        require(scenarioNamed(scenarios, "fleet-20").ships == 20,
                "20-ship fixture no longer contains exactly 20 ships.");
        require(scenarioNamed(scenarios, "fleet-50").ships == 50,
                "50-ship fixture no longer contains exactly 50 ships.");
        require(scenarioNamed(scenarios, "fleet-100").ships == 100,
                "100-ship fixture no longer contains exactly 100 ships.");
        Scenario largeFleet = scenarioNamed(scenarios, "fleet-150");
        require(largeFleet.ships > 100 && !largeFleet.selected,
                "Large-fleet fixture must contain more than 100 unselected ships.");
        Scenario selectedFleet = scenarioNamed(scenarios, "fleet-100-selected");
        require(selectedFleet.ships == 100 && selectedFleet.selected,
                "Selected-fleet fixture must contain 100 selected ships.");

        World stationWorld = buildWorld(scenarioNamed(scenarios, "multi-large-station"));
        require(stationWorld.bases.size() >= 3, "Large-station fixture must contain multiple stations.");
        for (Base base : stationWorld.bases.values()) {
            require(!Rules.DEFAULT_BASE.equals(base.typeId),
                    "Large-station fixture fell back to the default outpost for " + base.id);
        }

        World resourceWorld = buildWorld(scenarioNamed(scenarios, "resource-field"));
        boolean rock = false;
        boolean gas = false;
        for (ResourceNode node : resourceWorld.resources) {
            rock |= node.kind == NodeKind.SILICATE_ROCK;
            gas |= node.kind == NodeKind.GAS_CLOUD;
        }
        require(resourceWorld.resources.size() >= 100 && rock && gas,
                "Resource-field fixture must contain a dense mix of asteroid/rock and gas nodes.");

        World combatWorld = buildWorld(scenarioNamed(scenarios, "heavy-combat"));
        require(combatWorld.units.size() >= 50 && combatWorld.shots.size() >= 100
                        && combatWorld.explosions.size() >= 32,
                "Heavy-combat fixture no longer exercises dense ships/projectiles/destruction VFX.");
        if (Rules.SHIPS.containsKey("battleship")) {
            require(combatWorld.units.values().stream().anyMatch(unit -> "battleship".equals(unit.shipTypeId)),
                    "Heavy-combat fixture must exercise combat-class authored hull/VFX rendering.");
        }
        releaseExplosions(combatWorld);

        World destructionWorld = buildWorld(scenarioNamed(scenarios, "capital-station-destruction"));
        require(destructionWorld.units.values().stream().anyMatch(unit -> "titan".equals(unit.shipTypeId)),
                "Capital-destruction fixture must contain capital ships.");
        require(destructionWorld.bases.size() >= 3
                        && destructionWorld.explosions.size() == ExplosionEffect.maxActiveEffectsForTest(),
                "Capital/station-destruction fixture must reach the production destruction admission cap.");
        releaseExplosions(destructionWorld);

        Scenario nebula = scenarioNamed(scenarios, "background-nebula-expanse");
        require(NEBULA_EXPANSE.equals(nebula.systemId) && nebula.backgroundOnly(),
                "Nebula Expanse fixture must isolate the authored background/celestial stack.");
    }

    private static Scenario scenarioNamed(List<Scenario> scenarios, String name) {
        for (Scenario scenario : scenarios) if (scenario.name.equals(name)) return scenario;
        throw new IllegalStateException("Missing render performance scenario: " + name);
    }

    private static void validateDestructionBudgets() {
        for (DestructionProfile profile : DestructionProfile.values()) {
            require(profile.particleBudget <= MAX_PARTICLE_BUDGET_PER_EFFECT,
                    profile + " exceeds the render-performance particle budget.");
            require(profile.debrisFragments <= MAX_DEBRIS_PER_EFFECT,
                    profile + " exceeds the render-performance debris budget.");
            require(profile.vents <= MAX_VENTS_PER_EFFECT,
                    profile + " exceeds the render-performance vent budget.");
            require(profile.lifetimeSeconds <= 75.0,
                    profile + " exceeds the bounded destruction lifetime.");
        }

        List<ExplosionEffect> effects = new ArrayList<>();
        int cap = ExplosionEffect.maxActiveEffectsForTest();
        for (int i = 0; i < cap + 32; i++) {
            DestructionProfile profile = i % 4 == 0 ? DestructionProfile.STATION : DestructionProfile.MAJOR;
            ExplosionEffect.admitValidationEffect(effects, profile);
            require(effects.size() <= cap, "Destruction admission exceeded its active-effect cap.");
        }
        require(effects.size() == cap, "Destruction admission cap did not retain the expected bounded set.");
        for (ExplosionEffect effect : effects) effect.update(1000);
        effects.clear();
    }

    private static void validateSpriteCacheBound() {
        Unit unit = new Unit("P1", 1, Rules.STARTING_SHIP, 0, 0);

        ShipSpriteCache.resetForTest();
        Color repeated = new Color(80, 190, 255);
        ShipSpriteCache.sprite(unit, repeated);
        ShipSpriteCache.sprite(unit, repeated);
        ShipSpriteCache.Snapshot accounting = ShipSpriteCache.snapshot();
        require(accounting.requests() == 2 && accounting.hits() == 1 && accounting.misses() == 1
                        && accounting.generations() == 1,
                "Ship sprite cache hit/miss/generation accounting failed its deterministic probe.");

        ShipSpriteCache.resetForTest();
        int requested = ShipSpriteCache.maxEntries() + 32;
        for (int i = 0; i < requested; i++) {
            unit.heading = 0;
            int rgb = 0xFF000000 | (i & 0x00FFFFFF);
            ShipSpriteCache.sprite(unit, new Color(rgb, true));
        }
        ShipSpriteCache.Snapshot snapshot = ShipSpriteCache.snapshot();
        require(snapshot.entries() <= ShipSpriteCache.maxEntries(), "Ship sprite cache exceeded hard limit.");
        require(snapshot.requests() == requested && snapshot.hits() == 0
                        && snapshot.misses() == requested && snapshot.generations() == requested,
                "Ship sprite cache overflow accounting is inconsistent.");
        require(snapshot.evictions() > 0,
                "Ship sprite cache did not evict after exceeding its configured limit.");
        ShipSpriteCache.resetForTest();
    }

    private static void releaseExplosions(World world) {
        for (ExplosionEffect effect : world.explosions) effect.update(1000);
        world.explosions.clear();
    }

    private static void enforceTimingBudgets(List<Result> results) {
        for (Result result : results) {
            require(result.p95Ms <= result.scenario.p95BudgetMs,
                    String.format(Locale.ROOT,
                            "%s zoom %.2f exceeded p95 budget: %.3f ms > %.3f ms",
                            result.scenario.name, result.zoom, result.p95Ms, result.scenario.p95BudgetMs));
        }
    }

    private static void compareBaseline(Path path, List<Result> current, double maxRegressionPercent) throws IOException {
        Map<String, Double> baseline = readBaselineP95(path);
        double multiplier = 1.0 + maxRegressionPercent / 100.0;
        for (Result result : current) {
            Double prior = baseline.get(result.key());
            if (prior == null || prior <= 0) continue;
            double allowed = prior * multiplier;
            require(result.p95Ms <= allowed,
                    String.format(Locale.ROOT,
                            "%s regressed %.1f%%: baseline p95 %.3f ms, current %.3f ms, allowed %.3f ms",
                            result.key(), (result.p95Ms / prior - 1.0) * 100.0, prior, result.p95Ms, allowed));
        }
    }

    private static Map<String, Double> readBaselineP95(Path path) throws IOException {
        Map<String, Double> out = new HashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("scene,")) continue;
            String[] columns = line.split(",", -1);
            if (columns.length < 14) continue;
            out.put(columns[0] + "|" + columns[1], Double.parseDouble(columns[9]));
        }
        return out;
    }

    private static void printResults(List<Result> results) {
        System.out.println("scene | zoom | entities U/B/R/S/FX | cold ms | p50 | p95 | max | incremental | cache hit | hit/miss | generations | cache entries/peak | cache MiB | sprite gen total/avg ms");
        for (Result r : results) {
            System.out.printf(Locale.ROOT,
                    "%s | %.2f | %d/%d/%d/%d/%d | %.3f | %.3f | %.3f | %.3f | %.3f | %.1f%% | %d/%d | %d | %d/%d | %.1f | %.3f/%.3f%n",
                    r.scenario.name, r.zoom,
                    r.scenario.ships, r.scenario.stations, r.scenario.resources, r.scenario.shots, r.scenario.explosions,
                    r.coldMs, r.p50Ms, r.p95Ms, r.maxMs, r.incrementalMs,
                    r.cacheHitRate * 100.0, r.cacheHits, r.cacheMisses, r.cacheGenerations,
                    r.cacheEntries, r.cachePeakEntries, r.cacheEstimatedBytes / 1024.0 / 1024.0,
                    r.cacheGenerationMs, r.cacheAverageGenerationMs);
        }
    }

    private static void printAttributedCosts(List<Result> results) {
        System.out.println("--- production-path render attribution (p50 ms; incremental values subtract matching empty-system background) ---");
        for (double zoom : ZOOMS) {
            Result background = resultNamed(results, "background-default", zoom);
            Result nebula = resultNamed(results, "background-nebula-expanse", zoom);
            Result stations = resultNamed(results, "multi-large-station", zoom);
            Result resources = resultNamed(results, "resource-field", zoom);
            Result combat = resultNamed(results, "heavy-combat", zoom);
            Result destruction = resultNamed(results, "capital-station-destruction", zoom);
            System.out.printf(Locale.ROOT,
                    "zoom %.2f | background/celestial %.3f | nebula stack %.3f | large stations +%.3f | asteroid+gas +%.3f | combat VFX +%.3f | destruction VFX +%.3f%n",
                    zoom, background.p50Ms, nebula.p50Ms, stations.incrementalMs, resources.incrementalMs,
                    combat.incrementalMs, destruction.incrementalMs);
        }
    }

    private static Result resultNamed(List<Result> results, String name, double zoom) {
        for (Result result : results) {
            if (result.scenario.name.equals(name) && Math.abs(result.zoom - zoom) < 0.000001) return result;
        }
        throw new IllegalStateException("Missing render result: " + name + " at zoom " + zoom);
    }

    private static void writeCsv(Path output, List<Result> results) throws IOException {
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        List<String> lines = new ArrayList<>();
        lines.add("scene,zoom,ships,selected,stations,resources,shots,explosions,cold_ms,p95_ms,p50_ms,max_ms,incremental_ms,cache_hit_rate,cache_requests,cache_hits,cache_misses,cache_generations,cache_entries,cache_peak_entries,cache_evictions,cache_generation_ms,cache_average_generation_ms,cache_estimated_bytes,p95_budget_ms");
        for (Result r : results) {
            lines.add(String.format(Locale.ROOT,
                    "%s,%.2f,%d,%s,%d,%d,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%d,%d,%d,%d,%d,%d,%.6f,%.6f,%d,%.6f",
                    r.scenario.name, r.zoom, r.scenario.ships, r.scenario.selected,
                    r.scenario.stations, r.scenario.resources, r.scenario.shots, r.scenario.explosions,
                    r.coldMs, r.p95Ms, r.p50Ms, r.maxMs, r.incrementalMs, r.cacheHitRate,
                    r.cacheRequests, r.cacheHits, r.cacheMisses, r.cacheGenerations,
                    r.cacheEntries, r.cachePeakEntries, r.cacheEvictions, r.cacheGenerationMs,
                    r.cacheAverageGenerationMs, r.cacheEstimatedBytes, r.scenario.p95BudgetMs));
        }
        Files.write(output, lines, StandardCharsets.UTF_8);
        System.out.println("Wrote render performance report: " + output.toAbsolutePath());
    }

    private static String backgroundKey(String systemId, double zoom) {
        return systemId + "|" + String.format(Locale.ROOT, "%.2f", zoom);
    }

    private static int p95Index(int count) {
        return Math.max(0, Math.min(count - 1, (int)Math.ceil(count * 0.95) - 1));
    }

    private static double nanosToMs(long nanos) { return nanos / 1_000_000.0; }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Scenario(
            String name,
            String systemId,
            int ships,
            boolean selected,
            int stations,
            int resources,
            int shots,
            int explosions,
            double p95BudgetMs) {
        boolean backgroundOnly() {
            return ships == 0 && stations == 0 && resources == 0 && shots == 0 && explosions == 0;
        }
    }

    private record Result(
            Scenario scenario,
            double zoom,
            double coldMs,
            double p50Ms,
            double p95Ms,
            double maxMs,
            double incrementalMs,
            double cacheHitRate,
            long cacheRequests,
            long cacheHits,
            long cacheMisses,
            long cacheGenerations,
            int cacheEntries,
            int cachePeakEntries,
            long cacheEvictions,
            double cacheGenerationMs,
            double cacheAverageGenerationMs,
            long cacheEstimatedBytes) {
        Result withIncrementalMs(double value) {
            return new Result(scenario, zoom, coldMs, p50Ms, p95Ms, maxMs, value,
                    cacheHitRate, cacheRequests, cacheHits, cacheMisses, cacheGenerations,
                    cacheEntries, cachePeakEntries, cacheEvictions, cacheGenerationMs,
                    cacheAverageGenerationMs, cacheEstimatedBytes);
        }
        String key() { return scenario.name + "|" + String.format(Locale.ROOT, "%.2f", zoom); }
    }

    private static final class Options {
        private final Path output;
        private final Path baseline;
        private final boolean enforceTiming;
        private final double maxRegressionPercent;

        private Options(Path output, Path baseline, boolean enforceTiming, double maxRegressionPercent) {
            this.output = output;
            this.baseline = baseline;
            this.enforceTiming = enforceTiming;
            this.maxRegressionPercent = maxRegressionPercent;
        }

        private static Options parse(String[] args) {
            Path output = null;
            Path baseline = null;
            boolean enforce = Boolean.getBoolean("starchem.renderPerf.enforceTiming");
            double regression = Double.parseDouble(System.getProperty(
                    "starchem.renderPerf.maxRegressionPercent",
                    Double.toString(DEFAULT_MAX_REGRESSION_PERCENT)));
            for (String arg : args) {
                if (arg.startsWith("--output=")) output = Path.of(arg.substring("--output=".length()));
                else if (arg.startsWith("--baseline=")) baseline = Path.of(arg.substring("--baseline=".length()));
                else if (arg.equals("--enforce-timing")) enforce = true;
                else if (arg.startsWith("--max-regression=")) {
                    regression = Double.parseDouble(arg.substring("--max-regression=".length()));
                }
            }
            return new Options(output, baseline, enforce, Math.max(0.0, regression));
        }
    }
}
