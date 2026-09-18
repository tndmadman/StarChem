package com.tndmadman.rts;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.Color;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/** Regression coverage for procedural Manufacturing Command thumbnails. */
public final class ProductionCatalogVisualsValidator {
    private static final int ICON_SIZE = 42;
    private static final Color OWNER = new Color(0x50BEFF);
    private static final Color ALTERNATE_OWNER = new Color(0xE26A5A);

    private ProductionCatalogVisualsValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem production catalog visuals validation passed.");
    }

    static void validateOrThrow() {
        require(ProductionCatalogVisuals.MANUFACTURING_PREVIEW_VARIANT == 0,
                "Manufacturing ship preview variant is not deterministic variant 0");
        validateShips();
        validateStations();
        validateFallbacks();
    }

    private static void validateShips() {
        List<String> hulls = new ArrayList<>(ProductionCatalogRules.buildableHullIds());
        require(hulls.size() >= 2, "fewer than two buildable hulls are available for visual comparison");

        ProductionCatalogVisuals.resetForTest();
        ShipSpriteCache.resetForTest();

        Icon first = ProductionCatalogVisuals.shipIcon(hulls.get(0), OWNER, ICON_SIZE);
        require(first != null, "first buildable hull did not produce a procedural thumbnail");
        require(ShipSpriteCache.snapshot().generations() == 1,
                "first ship thumbnail did not generate through ShipSpriteCache exactly once");

        Icon firstAgain = ProductionCatalogVisuals.shipIcon(hulls.get(0), OWNER, ICON_SIZE);
        require(firstAgain == first, "final Manufacturing ship thumbnail was not cached");
        require(ShipSpriteCache.snapshot().generations() == 1,
                "repeated ship thumbnail request regenerated procedural ship art");

        Icon second = ProductionCatalogVisuals.shipIcon(hulls.get(1), OWNER, ICON_SIZE);
        require(second != null, "second buildable hull did not produce a procedural thumbnail");
        require(fingerprint(first) != fingerprint(second),
                "different buildable hull IDs produced identical Manufacturing thumbnails");

        Icon recolored = ProductionCatalogVisuals.shipIcon(hulls.get(0), ALTERNATE_OWNER, ICON_SIZE);
        require(recolored != null && fingerprint(first) != fingerprint(recolored),
                "ship thumbnail does not respond to owner/faction color");
        validateTransparentThumbnail(first, "ship");
    }

    private static void validateStations() {
        List<String> stations = new ArrayList<>(ProductionCatalogRules.buildableStationPackageIds());
        require(stations.size() >= 2, "fewer than two buildable station packages are available for visual comparison");

        ProductionCatalogVisuals.resetForTest();
        StationSpriteCache.resetForTest();

        Icon first = ProductionCatalogVisuals.stationIcon(stations.get(0), OWNER, ICON_SIZE);
        require(first != null, "first buildable station package did not produce a procedural thumbnail");
        require(StationSpriteCache.snapshot().runtimeGenerations() == 1,
                "first station thumbnail did not generate through StationSpriteCache exactly once");

        Icon firstAgain = ProductionCatalogVisuals.stationIcon(stations.get(0), OWNER, ICON_SIZE);
        require(firstAgain == first, "final Manufacturing station thumbnail was not cached");
        require(StationSpriteCache.snapshot().runtimeGenerations() == 1,
                "repeated station thumbnail request regenerated procedural station art");

        Icon second = ProductionCatalogVisuals.stationIcon(stations.get(1), OWNER, ICON_SIZE);
        require(second != null, "second buildable station package did not produce a procedural thumbnail");
        require(fingerprint(first) != fingerprint(second),
                "different station type IDs produced identical Manufacturing thumbnails");

        Icon recolored = ProductionCatalogVisuals.stationIcon(stations.get(0), ALTERNATE_OWNER, ICON_SIZE);
        require(recolored != null && fingerprint(first) != fingerprint(recolored),
                "station thumbnail does not respond to owner/faction color");
        validateTransparentThumbnail(first, "station");
    }

    private static void validateFallbacks() {
        require(ProductionCatalogVisuals.shipIcon("__missing_ship__", OWNER, ICON_SIZE) == null,
                "unknown ship ID did not return null for ProductionGlyph fallback");
        require(ProductionCatalogVisuals.stationIcon("__missing_station__", OWNER, ICON_SIZE) == null,
                "unknown station ID did not return null for ProductionGlyph fallback");
        require(ProductionCatalogVisuals.cachedIconCount() > 0,
                "Manufacturing thumbnail cache did not retain generated icons");
    }

    private static void validateTransparentThumbnail(Icon icon, String label) {
        BufferedImage image = image(icon);
        require(image.getWidth() == ICON_SIZE && image.getHeight() == ICON_SIZE,
                label + " thumbnail did not retain the requested square icon size");
        boolean transparent = false;
        boolean visible = false;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = (image.getRGB(x, y) >>> 24) & 0xFF;
                transparent |= alpha == 0;
                visible |= alpha > 0;
            }
        }
        require(transparent, label + " thumbnail lost alpha transparency");
        require(visible, label + " thumbnail contains no visible procedural artwork");
    }

    private static long fingerprint(Icon icon) {
        BufferedImage image = image(icon);
        long hash = 0xcbf29ce484222325L;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                hash ^= image.getRGB(x, y);
                hash *= 0x100000001b3L;
            }
        }
        return hash;
    }

    private static BufferedImage image(Icon icon) {
        require(icon instanceof ImageIcon, "procedural thumbnail is not an ImageIcon");
        Image raw = ((ImageIcon)icon).getImage();
        require(raw instanceof BufferedImage, "procedural thumbnail is not backed by a BufferedImage");
        return (BufferedImage)raw;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException("Production catalog visuals validation failed: " + message);
    }
}
