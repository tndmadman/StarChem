package com.tndmadman.rts;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Set;

/**
 * Headless regression/stress validator for the graphics overhaul. It exercises the same render
 * entry points used by normal gameplay without requiring a Swing window or network session.
 */
public final class GraphicsOverhaulValidator {
    private static final long GENEROUS_TOTAL_BUDGET_MS = 12_000;

    private GraphicsOverhaulValidator() { }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        long started = System.nanoTime();
        ArtAssetManager.clear();
        ShipSpriteCache.clear();

        BufferedImage frame = new BufferedImage(1600, 900, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = frame.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        validateSystemThemes(g);
        validateShipIdentitiesAndCache(g);
        validateStations(g);
        validateResources(g);
        g.dispose();

        ArtAssetManager.Stats art = ArtAssetManager.stats();
        ShipSpriteCache.Stats sprites = ShipSpriteCache.stats();
        require(art.imageCount() <= 256, "art cache exceeded its image bound");
        require(art.missingCount() <= 512, "negative art cache exceeded its bound");
        require(sprites.entries() <= 1536, "ship sprite cache exceeded its entry bound");
        require(SpaceBackgroundRenderer.cachedSceneCount() <= 18, "background scene cache exceeded its bound");
        require(sprites.entries() > 0, "ship sprite cache was not exercised");
        require(sprites.cacheHits() > 0, "ship sprite cache did not record reuse");

        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        require(elapsedMs <= GENEROUS_TOTAL_BUDGET_MS,
                "graphics validator exceeded generous regression ceiling: " + elapsedMs + " ms");
        System.out.println("Graphics overhaul validation passed in " + elapsedMs + " ms"
                + " | ships=" + Rules.SHIPS.size()
                + " | spriteEntries=" + sprites.entries()
                + " | spriteHits=" + sprites.cacheHits()
                + " | artImages=" + art.imageCount()
                + " | missingFallbacks=" + art.missingCount()
                + " | backgroundScenes=" + SpaceBackgroundRenderer.cachedSceneCount());
    }

    private static void validateSystemThemes(Graphics2D g) {
        List<StarSystemDefinition> themes = List.of(
                synthetic("validator_nebula", "gas", Set.of("gas_rich", "sensor_interference")),
                synthetic("validator_ice", "frontier", Set.of("cold")),
                synthetic("validator_forge", "industrial", Set.of()),
                synthetic("validator_graveyard", "frontier", Set.of("warzone")));
        for (int i = 0; i < themes.size(); i++) {
            Graphics2D bg = (Graphics2D)g.create();
            bg.translate((i % 2) * 800, (i / 2) * 450);
            bg.scale(.04, .04);
            bg.clipRect(0, 0, 20_000, 11_250);
            SpaceBackgroundRenderer.draw(bg, themes.get(i), i * 13.0);
            bg.dispose();
        }
    }

    private static StarSystemDefinition synthetic(String id, String role, Set<String> tags) {
        return new StarSystemDefinition(id, id, role, 20_000, 11_250,
                List.of(), List.of(), List.of(), tags, SystemModifiers.STANDARD);
    }

    private static void validateShipIdentitiesAndCache(Graphics2D g) {
        require(!Rules.SHIPS.isEmpty(), "no ship rules available for graphics validation");
        int index = 0;
        for (var type : Rules.SHIPS.values()) {
            Graphics2D ship = (Graphics2D)g.create();
            int col = index % 8;
            int row = (index / 8) % 5;
            ship.translate(85 + col * 190, 70 + row * 165);
            ship.scale(.62, .62);
            ShipVisualRenderer.draw(ship, type, new Color(70, 175, 235));
            ship.dispose();

            Unit unit = new Unit("SOLO", index + 1, type.id, 0, 0);
            for (int bucket = 0; bucket < 24; bucket++) {
                unit.heading = bucket * Math.PI * 2 / 24.0;
                require(ShipSpriteCache.sprite(unit, new Color(70, 175, 235)) != null,
                        "sprite generation failed for " + type.id);
            }
            // Explicit reuse must be a cache hit.
            unit.heading = 0;
            ShipSpriteCache.sprite(unit, new Color(70, 175, 235));
            index++;
        }
    }

    private static void validateStations(Graphics2D g) {
        String[] ids = {"outpost", "shipyard", "manufacturing", "laboratory"};
        int rendered = 0;
        for (int i = 0; i < ids.length; i++) {
            String id = ids[i];
            if (Rules.findBase(id) == null) continue;
            Base base = new Base("VAL:B" + i, "SOLO", id, 210 + i * 300, 745);
            double radius = id.equals("shipyard") ? 82 : id.equals("manufacturing") || id.equals("laboratory") ? 74 : 64;
            StationVisualRenderer.draw(g, base, radius, new Color(70, 175, 235));
            rendered++;
        }
        require(rendered >= 2, "representative station rules were not available");
    }

    private static void validateResources(Graphics2D g) {
        ResourceNode rock = new ResourceNode(99101, "Validator Iron", NodeKind.SILICATE_ROCK,
                Material.IRON, 1330, 720, 100, 8, 20);
        ResourceNode gas = new ResourceNode(99102, "Validator Hydrogen", NodeKind.GAS_CLOUD,
                Material.HYDROGEN, 1460, 720, 100, 8, 24);
        ResourceVisualRenderer.draw(g, rock, true);
        ResourceVisualRenderer.draw(g, gas, true);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
