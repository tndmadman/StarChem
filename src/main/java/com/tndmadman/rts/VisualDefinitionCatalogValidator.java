package com.tndmadman.rts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Headless validation for issue #385 presentation data, content references, and fallback-safe bounds. */
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
        for (String id : new String[]{"sol_standard", "nebula_expanse", "red_dwarf", "volcanic_crucible", "pulsar_reach"}) {
            require(catalog.containsSystem(id), "Missing required system visual: " + id);
        }

        validateContentReferences(catalog);
        validateDistinctness(catalog);
        validateMalformedRejection();
        validateOutOfBoundsRejection();
        System.out.println("Rich visual definition validation passed: " + path
                + " | ships=" + catalog.shipCount()
                + " stations=" + catalog.stationCount()
                + " celestials=" + catalog.celestialCount()
                + " systems=" + catalog.systemCount());
    }

    private static void validateContentReferences(VisualDefinitionCatalog catalog) {
        for (ShipVisualDefinition visual : catalog.shipDefinitions()) {
            ShipType ship = Rules.findShip(visual.id());
            require(ship != null, "Visual definition references unknown ship: " + visual.id());
            require(visual.hardpointCount() <= ship.weaponHardpoints,
                    "Visual hardpoint count exceeds gameplay hardpoints for " + visual.id()
                            + ": visual=" + visual.hardpointCount() + " gameplay=" + ship.weaponHardpoints);
        }
        for (StationVisualDefinition visual : catalog.stationDefinitions()) {
            require(Rules.findBase(visual.id()) != null,
                    "Visual definition references unknown station: " + visual.id());
        }

        Map<String,StarSystemDefinition> systems = new LinkedHashMap<>();
        for (StarSystemDefinition system : StarSystems.options()) systems.put(system.id(), system);
        for (SystemVisualDefinition visual : catalog.systemDefinitions()) {
            require(systems.containsKey(visual.id()),
                    "Backdrop references unknown star system: " + visual.id());
        }
        for (CelestialVisualDefinition visual : catalog.celestialDefinitions()) {
            StarSystemDefinition system = systems.get(visual.systemId());
            require(system != null,
                    "Celestial visual references unknown star system: " + visual.systemId());
            boolean bodyExists = system.bodies().stream().anyMatch(body -> body.id().equals(visual.bodyId()));
            require(bodyExists,
                    "Celestial visual references unknown body: " + visual.systemId() + ":" + visual.bodyId());
        }
    }

    private static void validateDistinctness(VisualDefinitionCatalog catalog) {
        Set<String> shipSignatures = new HashSet<>();
        for (ShipVisualDefinition v : catalog.shipDefinitions()) {
            shipSignatures.add(v.preset() + ":" + v.lengthScale() + ":" + v.widthScale() + ":" + v.asymmetry()
                    + ":" + v.armorSections() + ":" + v.engineCount() + ":" + v.podCount() + ":"
                    + v.hardpointCount() + ":" + v.hangarCount() + ":" + v.antennaCount() + ":"
                    + v.accentRgb() + ":" + v.glowRgb() + ":" + v.marking());
        }
        require(shipSignatures.size() >= 10,
                "At least 10 ships must have meaningfully distinct authored visual signatures.");

        Set<StationVisualPreset> stationPresets = new HashSet<>();
        for (StationVisualDefinition v : catalog.stationDefinitions()) stationPresets.add(v.preset());
        require(stationPresets.size() >= 4,
                "Major production stations must use recognizable, distinct structural presets.");

        Set<CelestialVisualClass> celestialClasses = new HashSet<>();
        for (CelestialVisualDefinition v : catalog.celestialDefinitions()) celestialClasses.add(v.visualClass());
        require(celestialClasses.contains(CelestialVisualClass.STAR), "Celestial visuals must include a normal star.");
        require(celestialClasses.contains(CelestialVisualClass.RED_STAR), "Celestial visuals must include a red star.");
        require(celestialClasses.contains(CelestialVisualClass.PULSAR), "Celestial visuals must include a pulsar.");
        require(celestialClasses.contains(CelestialVisualClass.GAS_GIANT), "Celestial visuals must include a gas giant.");
        require(celestialClasses.size() >= 7, "Celestial visuals must exercise several visibly distinct classes.");

        require(new HashSet<>(catalog.systemDefinitions()).size() >= 3,
                "Several star systems must have distinct backdrop definitions.");
    }

    private static void validateMalformedRejection() throws IOException {
        Path temp = Files.createTempFile("starchem-visual-malformed-", ".json");
        try {
            Files.writeString(temp, "{\"version\":1,\"ships\":[{\"id\":\"bad\",\"preset\":\"NOT_A_SHAPE\"}],\"stations\":[],\"celestials\":[],\"systems\":[]}");
            boolean rejected = false;
            try {
                VisualDefinitionCatalog.loadForValidation(temp);
            } catch (IllegalStateException expected) {
                rejected = true;
            }
            require(rejected, "Malformed visual enum values must be rejected.");
        } finally {
            Files.deleteIfExists(temp);
        }
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
