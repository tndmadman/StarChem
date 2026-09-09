package com.tndmadman.rts;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.EnumSet;

/** Headless regression checks for issue #392 celestial visual generation and compositing. */
public final class CelestialRenderValidator {
    private CelestialRenderValidator() { }

    public static void main(String[] args) {
        require(CelestialVisualCatalog.configuredPresetCount() > CelestialVisualClass.values().length,
                "authored celestial visual config did not load");
        validateVisualClassesAndCoverage();
        validateConfiguredFeatures();
        validateDeterminism();
        validateVariation();
        validateClassDistinctness();
        validateAuthoredParameterIsolation();
        validateDirectionalLighting();
        validateRingComposite();
        validateCacheReuse();
        validateCacheBound();
        System.out.println("StarChem celestial render validation passed.");
    }

    private static void validateVisualClassesAndCoverage() {
        EnumSet<CelestialVisualClass> classes = EnumSet.noneOf(CelestialVisualClass.class);
        int configuredBodies = 0;
        for (StarSystemDefinition system : StarSystems.options()) {
            for (CelestialBodyDefinition body : system.bodies()) {
                CelestialVisualDefinition visual = CelestialVisualCatalog.resolve(system.id(), body);
                require(!visual.id().startsWith("fallback-"),
                        "configured celestial body is missing an authored visual mapping: " + system.id() + "/" + body.id());
                classes.add(visual.visualClass());
                configuredBodies++;
            }
        }
        require(configuredBodies > 0, "no configured celestial bodies were validated");

        // Desert is supported even though the current system roster has no authored desert world yet.
        classes.add(CelestialVisualCatalog.resolve("unconfigured",
                body("dune", "Desert World", "sun", new Color(188, 126, 68))).visualClass());
        require(classes.containsAll(EnumSet.allOf(CelestialVisualClass.class)),
                "not every supported celestial visual class resolves from authored/fallback content: " + classes);
    }

    private static void validateConfiguredFeatures() {
        CelestialVisualDefinition terrestrial = visual("sol_standard", "inner");
        CelestialVisualDefinition gasGiant = visual("sol_standard", "giant");
        CelestialVisualDefinition industrial = visual("gas_giant_frontier", "moon_a");
        CelestialVisualDefinition star = visual("sol_standard", "sun");
        CelestialVisualDefinition toxic = visual("carbon_basin", "mire");

        require(terrestrial.visualClass() == CelestialVisualClass.TERRESTRIAL,
                "Sol inner planet is not authored as terrestrial");
        require(terrestrial.hasAtmosphere() && terrestrial.hasClouds(),
                "terrestrial atmosphere/cloud configuration is missing");
        require(gasGiant.visualClass() == CelestialVisualClass.GAS_GIANT && gasGiant.hasRings()
                        && gasGiant.hasAtmosphere() && gasGiant.hasClouds(),
                "gas giant rings/atmosphere/cloud configuration is missing");
        require(industrial.visualClass() == CelestialVisualClass.INDUSTRIAL && industrial.emissive(),
                "industrial moon emissive configuration is missing");
        require(star.visualClass() == CelestialVisualClass.STAR && star.emissive(),
                "star visual/emissive configuration is missing");
        require(toxic.visualClass() == CelestialVisualClass.TOXIC && toxic.hasAtmosphere() && toxic.hasClouds(),
                "toxic-world atmosphere/cloud configuration is missing");
    }

    private static void validateDeterminism() {
        CelestialVisualDefinition visual = visual("sol_standard", "inner");
        long first = CelestialSpriteCache.pixelHash(visual, 0x392L);
        long second = CelestialSpriteCache.pixelHash(visual, 0x392L);
        require(first == second, "same celestial definition and seed produced different cached artwork");
    }

    private static void validateVariation() {
        CelestialVisualDefinition visual = visual("sol_standard", "rock");
        long first = CelestialSpriteCache.pixelHash(visual, 11);
        long second = CelestialSpriteCache.pixelHash(visual, 12);
        require(first != second, "different celestial detail seeds produced identical artwork");
    }

    private static void validateClassDistinctness() {
        long seed = 0x3925L;
        long terrestrial = CelestialSpriteCache.pixelHash(visual("sol_standard", "inner"), seed);
        long rocky = CelestialSpriteCache.pixelHash(visual("sol_standard", "rock"), seed);
        long gas = CelestialSpriteCache.pixelHash(visual("sol_standard", "giant"), seed);
        require(terrestrial != rocky && terrestrial != gas && rocky != gas,
                "terrestrial, rocky, and gas-giant artwork are not visually distinct");
    }

    private static void validateAuthoredParameterIsolation() {
        CelestialVisualDefinition base = visual("sol_standard", "inner");
        CelestialVisualDefinition dry = withCloudCoverage(base, 0);
        CelestialVisualDefinition cloudy = withCloudCoverage(base, 0.92);
        long seed = 0xC10D5L;
        long dryHash = CelestialSpriteCache.pixelHash(dry, seed);
        long cloudyHash = CelestialSpriteCache.pixelHash(cloudy, seed);
        require(dryHash != cloudyHash,
                "cloud coverage did not produce distinct cached artwork; cache identity is missing authored parameters");
    }

    private static void validateDirectionalLighting() {
        Color flat = new Color(150, 150, 150);
        CelestialVisualDefinition visual = new CelestialVisualDefinition(
                "lighting-direction-test", CelestialVisualClass.ROCKY,
                flat, flat, flat, flat, 0, 0, 0, 0, 0.36, 0, flat, false);
        int size = 320;
        int center = size / 2;
        int radius = 72;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        CelestialRenderer.draw(g, "", center, center, radius, 0, center, visual, 0x1A17L);
        g.dispose();

        double lit = hemisphereLuma(image, center, center, radius, true);
        double dark = hemisphereLuma(image, center, center, radius, false);
        require(lit > dark + 12,
                "directional lighting/terminator does not leave the star-facing hemisphere visibly brighter");
    }

    private static void validateRingComposite() {
        CelestialVisualDefinition visual = visual("sol_standard", "giant");
        int size = 360;
        int center = size / 2;
        int radius = 62;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        CelestialRenderer.draw(g, "", center, center, radius, 20, center, visual, 0xA11CEL);
        g.dispose();

        int outsideBody = 0;
        double inner = radius * 1.25;
        double outer = radius * 2.25;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double distance = Math.hypot(x - center, y - center);
                if (distance >= inner && distance <= outer && ((image.getRGB(x, y) >>> 24) & 0xFF) > 0) outsideBody++;
            }
        }
        require(outsideBody > 40, "configured ring system did not composite beyond the planet silhouette");
    }

    private static void validateCacheReuse() {
        CelestialVisualDefinition visual = visual("gas_giant_frontier", "leviathan");
        int before = CelestialSpriteCache.cacheSize();
        CelestialSpriteCache.Sprite first = CelestialSpriteCache.sprite(visual, 99);
        int afterFirst = CelestialSpriteCache.cacheSize();
        CelestialSpriteCache.Sprite second = CelestialSpriteCache.sprite(visual, 99);
        int afterSecond = CelestialSpriteCache.cacheSize();
        require(first == second, "repeated celestial render did not reuse the cached sprite");
        require(afterFirst >= before && afterSecond == afterFirst, "cache grew on an identical celestial lookup");
    }

    private static void validateCacheBound() {
        CelestialVisualDefinition visual = visual("sol_standard", "rock");
        int cap = CelestialSpriteCache.maxEntries();
        for (int i = 0; i < cap + 24; i++) CelestialSpriteCache.sprite(visual, 10_000L + i);
        require(CelestialSpriteCache.cacheSize() <= cap,
                "celestial sprite cache exceeded its configured bound: " + CelestialSpriteCache.cacheSize() + "/" + cap);
    }

    private static CelestialVisualDefinition visual(String systemId, String bodyId) {
        return CelestialVisualCatalog.resolve(systemId, body(systemId, bodyId));
    }

    private static CelestialBodyDefinition body(String systemId, String bodyId) {
        for (CelestialBodyDefinition body : StarSystems.get(systemId).bodies()) {
            if (body.id().equals(bodyId)) return body;
        }
        throw new IllegalStateException("Missing celestial body " + systemId + "/" + bodyId);
    }

    private static CelestialBodyDefinition body(String id, String name, String parent, Color color) {
        return new CelestialBodyDefinition(id, name, parent, parent == null ? 0 : 1000, 60, 0.01, color);
    }

    private static CelestialVisualDefinition withCloudCoverage(CelestialVisualDefinition visual, double coverage) {
        return new CelestialVisualDefinition(
                visual.id(), visual.visualClass(), visual.primary(), visual.secondary(), visual.accent(), visual.atmosphere(),
                visual.atmosphereStrength(), coverage, visual.ringInnerRadius(), visual.ringOuterRadius(),
                visual.ringFlattening(), visual.ringAngle(), visual.ringColor(), visual.emissive());
    }

    private static double hemisphereLuma(BufferedImage image, int cx, int cy, int radius, boolean left) {
        double total = 0;
        int count = 0;
        double limit = radius * 0.72;
        for (int y = cy - radius; y <= cy + radius; y++) {
            for (int x = cx - radius; x <= cx + radius; x++) {
                if (Math.hypot(x - cx, y - cy) > limit) continue;
                if (left ? x >= cx - 6 : x <= cx + 6) continue;
                int argb = image.getRGB(x, y);
                if (((argb >>> 24) & 0xFF) == 0) continue;
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                total += r * 0.2126 + g * 0.7152 + b * 0.0722;
                count++;
            }
        }
        require(count > 0, "directional lighting validator sampled no rendered pixels");
        return total / count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
