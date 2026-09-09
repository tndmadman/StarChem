package com.tndmadman.rts;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Headless acceptance coverage for issue #393 authored ship identities. */
public final class ShipVisualValidator {
    private static final Color TEST_COLOR = new Color(72, 164, 224);
    private static final List<String> COMBAT = List.of("frigate", "destroyer", "cruiser", "battle_cruiser", "battleship");
    private static final List<String> CAPITALS = List.of("carrier", "dreadnought", "supercarrier", "titan", "monolith");

    private ShipVisualValidator() { }

    public static void main(String[] args) {
        List<String> errors = validate();
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Ship visual validation failed:\n - " + String.join("\n - ", errors));
        }
        System.out.println("Ship visual validation passed for " + Rules.SHIPS.size() + " configured ship types.");
    }

    static List<String> validate() {
        List<String> errors = new ArrayList<>();
        validateCoverage(errors);
        validateHardpoints(errors);
        validateCombatSilhouettes(errors);
        validateIndustrialIdentity(errors);
        validateCapitalHierarchy(errors);
        validateDeterminismAndCacheBounds(errors);
        return List.copyOf(errors);
    }

    private static void validateCoverage(List<String> errors) {
        for (ShipType type : Rules.SHIPS.values()) {
            if (!ShipVisualCatalog.hasExplicit(type.id)) errors.add("Missing authored visual definition for " + type.id + ".");
        }
    }

    private static void validateHardpoints(List<String> errors) {
        for (ShipType type : Rules.SHIPS.values()) {
            ShipVisualDefinition visual = ShipVisualCatalog.forType(type);
            if (visual.hasFeature(ShipVisualDefinition.Feature.ANONYMOUS_CONTACT)) continue;
            int visualHardpoints = visual.mountCount(ShipVisualDefinition.MountKind.HARDPOINT);
            if (visualHardpoints != type.weaponHardpoints) {
                errors.add(type.id + " has " + type.weaponHardpoints + " gameplay hardpoints but "
                        + visualHardpoints + " authored hardpoint locations.");
            }
        }
    }

    private static void validateCombatSilhouettes(List<String> errors) {
        Set<String> fingerprints = new HashSet<>();
        for (String id : COMBAT) {
            ShipType type = Rules.findShip(id);
            if (type == null) {
                errors.add("Combat visual acceptance ship is missing from rules: " + id + ".");
                continue;
            }
            String fingerprint = normalizedFingerprint(ShipVisualCatalog.forType(type));
            if (!fingerprints.add(fingerprint)) errors.add("Combat silhouette is only a normalized variant of another class: " + id + ".");
        }
    }

    private static void validateIndustrialIdentity(List<String> errors) {
        requireFeature(errors, "hauler", ShipVisualDefinition.Feature.CARGO_MODULES);
        requireFeature(errors, "freighter", ShipVisualDefinition.Feature.CARGO_MODULES);
        requireFeature(errors, "deep_miner", ShipVisualDefinition.Feature.MINING_GEAR);
        requireFeature(errors, "gas_harvester", ShipVisualDefinition.Feature.GAS_GEAR);
        requireFeature(errors, "station_builder", ShipVisualDefinition.Feature.CONSTRUCTION_GEAR);
        requireFeature(errors, "salvager", ShipVisualDefinition.Feature.SALVAGE_GEAR);
    }

    private static void validateCapitalHierarchy(List<String> errors) {
        int previousRank = -1;
        for (String id : CAPITALS) {
            ShipType type = Rules.findShip(id);
            if (type == null) {
                errors.add("Capital visual acceptance ship is missing from rules: " + id + ".");
                continue;
            }
            ShipVisualDefinition visual = ShipVisualCatalog.forType(type);
            if (!visual.hasFeature(ShipVisualDefinition.Feature.CAPITAL)) errors.add(id + " is missing CAPITAL visual identity.");
            if (visual.complexityRank() <= previousRank) errors.add("Capital visual complexity does not increase at " + id + ".");
            previousRank = visual.complexityRank();
        }
        ShipType monolith = Rules.findShip("monolith");
        if (monolith != null && !ShipVisualCatalog.forType(monolith).hasFeature(ShipVisualDefinition.Feature.MEGASTRUCTURE)) {
            errors.add("monolith is missing MEGASTRUCTURE visual identity.");
        }
    }

    private static void validateDeterminismAndCacheBounds(List<String> errors) {
        for (ShipType type : Rules.SHIPS.values()) {
            double rasterScale = ShipSpriteCache.rasterScale(type);
            if (!Double.isFinite(rasterScale) || rasterScale <= 0 || rasterScale > 1.0) {
                errors.add(type.id + " has invalid medium-LOD raster scale " + rasterScale + ".");
                continue;
            }
            BufferedImage first = render(type, rasterScale);
            BufferedImage second = render(type, rasterScale);
            if (!samePixels(first, second)) errors.add(type.id + " visual rendering is not deterministic.");
            if (isEmpty(first)) errors.add(type.id + " rendered no visible pixels.");
            if (touchesCacheEdge(first, 2)) errors.add(type.id + " risks clipping in the 144px medium-LOD sprite cache.");
        }
    }

    private static void requireFeature(List<String> errors, String id, ShipVisualDefinition.Feature feature) {
        ShipType type = Rules.findShip(id);
        if (type == null) {
            errors.add("Industrial visual acceptance ship is missing from rules: " + id + ".");
            return;
        }
        if (!ShipVisualCatalog.forType(type).hasFeature(feature)) errors.add(id + " is missing visual feature " + feature + ".");
    }

    private static BufferedImage render(ShipType type, double rasterScale) {
        int size = ShipSpriteCache.imageSize();
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.translate(size / 2.0, size / 2.0);
        g.scale(rasterScale, rasterScale);
        ShipShape.draw(g, type, TEST_COLOR);
        g.dispose();
        return image;
    }

    private static boolean samePixels(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) if (a.getRGB(x, y) != b.getRGB(x, y)) return false;
        }
        return true;
    }

    private static boolean isEmpty(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) if ((image.getRGB(x, y) >>> 24) != 0) return false;
        }
        return true;
    }

    private static boolean touchesCacheEdge(BufferedImage image, int margin) {
        int maxX = image.getWidth() - 1;
        int maxY = image.getHeight() - 1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (x > margin && x < maxX - margin && y > margin && y < maxY - margin) continue;
                if ((image.getRGB(x, y) >>> 24) != 0) return true;
            }
        }
        return false;
    }

    private static String normalizedFingerprint(ShipVisualDefinition visual) {
        double[][] points = visual.normalizedOutline();
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (double[] point : points) {
            minX = Math.min(minX, point[0]);
            maxX = Math.max(maxX, point[0]);
            minY = Math.min(minY, point[1]);
            maxY = Math.max(maxY, point[1]);
        }
        double width = Math.max(0.000001, maxX - minX);
        double height = Math.max(0.000001, maxY - minY);
        StringBuilder fingerprint = new StringBuilder(points.length * 10);
        for (double[] point : points) {
            long nx = Math.round((point[0] - minX) / width * 1000);
            long ny = Math.round((point[1] - minY) / height * 1000);
            fingerprint.append(nx).append(':').append(ny).append(';');
        }
        return fingerprint.toString();
    }
}
