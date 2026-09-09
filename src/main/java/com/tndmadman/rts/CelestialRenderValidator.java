package com.tndmadman.rts;

import java.awt.Color;
import java.util.EnumSet;

/** Headless regression checks for issue #392 celestial visual generation. */
public final class CelestialRenderValidator {
    private CelestialRenderValidator() { }

    public static void main(String[] args) {
        require(CelestialVisualCatalog.configuredPresetCount() > 11, "authored celestial visual config did not load");
        validateVisualClasses();
        validateDeterminism();
        validateVariation();
        validateCacheReuse();
        System.out.println("StarChem celestial render validation passed.");
    }

    private static void validateVisualClasses() {
        EnumSet<CelestialVisualClass> classes = EnumSet.noneOf(CelestialVisualClass.class);
        String[] systems = {"sol_standard", "gas_giant_frontier", "nebula_expanse", "binary_forge", "ancient_graveyard"};
        CelestialBodyDefinition[] bodies = {
                body("sun", "Sun", null, new Color(255, 205, 80)),
                body("inner", "Inner Planet", "sun", new Color(80, 145, 210)),
                body("rock", "Rock Planet", "sun", new Color(160, 115, 75)),
                body("leviathan", "Leviathan", "white_star", new Color(201, 149, 98)),
                body("tomb", "Tomb World", "sun", new Color(85, 90, 99))
        };
        for (int i = 0; i < bodies.length; i++) classes.add(CelestialVisualCatalog.resolve(systems[i], bodies[i]).visualClass());
        require(classes.size() >= 5, "fewer than five celestial visual classes resolve from representative content: " + classes);
    }

    private static void validateDeterminism() {
        CelestialVisualDefinition visual = CelestialVisualCatalog.resolve("sol_standard",
                body("inner", "Inner Planet", "sun", new Color(80, 145, 210)));
        long first = CelestialSpriteCache.pixelHash(visual, 0x392L);
        long second = CelestialSpriteCache.pixelHash(visual, 0x392L);
        require(first == second, "same celestial definition and seed produced different cached artwork");
    }

    private static void validateVariation() {
        CelestialVisualDefinition visual = CelestialVisualCatalog.resolve("sol_standard",
                body("rock", "Rock Planet", "sun", new Color(160, 115, 75)));
        long first = CelestialSpriteCache.pixelHash(visual, 11);
        long second = CelestialSpriteCache.pixelHash(visual, 12);
        require(first != second, "different celestial detail seeds produced identical artwork");
    }

    private static void validateCacheReuse() {
        CelestialVisualDefinition visual = CelestialVisualCatalog.resolve("gas_giant_frontier",
                body("leviathan", "Leviathan", "white_star", new Color(201, 149, 98)));
        int before = CelestialSpriteCache.cacheSize();
        CelestialSpriteCache.Sprite first = CelestialSpriteCache.sprite(visual, 99);
        int afterFirst = CelestialSpriteCache.cacheSize();
        CelestialSpriteCache.Sprite second = CelestialSpriteCache.sprite(visual, 99);
        int afterSecond = CelestialSpriteCache.cacheSize();
        require(first == second, "repeated celestial render did not reuse the cached sprite");
        require(afterFirst >= before && afterSecond == afterFirst, "cache grew on an identical celestial lookup");
    }

    private static CelestialBodyDefinition body(String id, String name, String parent, Color color) {
        return new CelestialBodyDefinition(id, name, parent, parent == null ? 0 : 1000, 60, 0.01, color);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
