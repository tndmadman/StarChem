package com.tndmadman.rts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Headless validation for issue #385 presentation data and fallback-safe bounds. */
public final class VisualDefinitionCatalogValidator {
    private VisualDefinitionCatalogValidator() { }

    public static void main(String[] args) throws IOException {
        Path path = args.length > 0 ? Path.of(args[0]) : VisualDefinitionCatalog.CONFIG_PATH;
        VisualDefinitionCatalog catalog = VisualDefinitionCatalog.loadForValidation(path);

        require(catalog.shipCount() >= 10, "At least 10 ships must have authored visual definitions.");
        require(catalog.stationCount() >= 4, "Major production stations must have authored visual definitions.");
        require(catalog.celestialCount() >= 6, "Multiple celestial classes must be authored.");
        require(catalog.systemCount() >= 3, "Several system templates must have authored backdrops.");

        for (String id : new String[]{"prospector", "station_builder", "frigate", "destroyer", "cruiser",
                "battleship", "carrier", "dreadnought", "supercarrier", "titan"}) {
            require(catalog.containsShip(id), "Missing required ship visual: " + id);
        }
        for (String id : new String[]{"outpost", "shipyard", "laboratory", "manufacturing"}) {
            require(catalog.containsStation(id), "Missing required station visual: " + id);
        }
        for (String id : new String[]{"sol_standard", "nebula_expanse", "red_dwarf", "volcanic_crucible"}) {
            require(catalog.containsSystem(id), "Missing required system visual: " + id);
        }

        validateOutOfBoundsRejection();
        System.out.println("Rich visual definition validation passed: " + path
                + " | ships=" + catalog.shipCount()
                + " stations=" + catalog.stationCount()
                + " celestials=" + catalog.celestialCount()
                + " systems=" + catalog.systemCount());
    }

    private static void validateOutOfBoundsRejection() throws IOException {
        Path temp = Files.createTempFile("starchem-visual-invalid-", ".json");
        try {
            Files.writeString(temp, "{\"version\":1,\"ships\":[{\"id\":\"bad\",\"preset\":\"WEDGE\",\"lengthScale\":99}],\"stations\":[],\"celestials\":[],\"systems\":[]}");
            boolean rejected = false;
            try {
                VisualDefinitionCatalog.loadForValidation(temp);
            } catch (IllegalStateException expected) {
                rejected = true;
            }
            require(rejected, "Out-of-bounds visual values must be rejected.");
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
