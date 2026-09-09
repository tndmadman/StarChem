package com.tndmadman.rts;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Headless validation for issue #385 presentation data, references, rendering, and bounds. */
public final class VisualDefinitionCatalogValidator {
    private static final String[] AUTHORED_HULLS = {
            "prospector", "station_builder", "hauler", "deep_miner", "gas_harvester", "freighter", "salvager",
            "fuel_hauler_shuttle", "logistics_shuttle", "frigate", "destroyer", "cruiser", "battle_cruiser",
            "battleship", "carrier", "dreadnought", "supercarrier", "titan", "monolith"
    };

    private VisualDefinitionCatalogValidator() { }

    public static void main(String[] args) throws IOException {
        Path path = args.length > 0 ? Path.of(args[0]) : VisualDefinitionCatalog.CONFIG_PATH;
        VisualDefinitionCatalog catalog = VisualDefinitionCatalog.loadForValidation(path);

        require(catalog.shipCount() >= AUTHORED_HULLS.length, "All playable/functional hulls must have authored visuals.");
        require(catalog.stationCount() >= 4, "Major production stations must have authored visual definitions.");
        require(catalog.celestialCount() >= 6, "Multiple celestial classes must be authored.");
        require(catalog.systemCount() >= 3, "Several system templates must have authored backdrops.");

        for (String id : AUTHORED_HULLS) require(catalog.containsShip(id), "Missing authored hull visual: " + id);
        for (String id : new String[]{"outpost", "shipyard", "laboratory", "manufacturing"})
            require(catalog.containsStation(id), "Missing required station visual: " + id);
        for (String id : new String[]{"sol_standard", "nebula_expanse", "red_dwarf", "volcanic_crucible", "pulsar_reach"})
            require(catalog.containsSystem(id), "Missing required system visual: " + id);

        validateContentReferences(catalog);
        validateDistinctness(catalog);
        validateRichControls(catalog);
        validateHeadlessShipRendering(catalog);
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
            require(visual.hardpointCount() == ship.weaponHardpoints,
                    "Visual hardpoint count must match gameplay hardpoints for " + visual.id()
                            + ": visual=" + visual.hardpointCount() + " gameplay=" + ship.weaponHardpoints);
        }
        for (StationVisualDefinition visual : catalog.stationDefinitions())
            require(Rules.findBase(visual.id()) != null, "Visual definition references unknown station: " + visual.id());

        Map<String,StarSystemDefinition> systems = new LinkedHashMap<>();
        for (StarSystemDefinition system : StarSystems.options()) systems.put(system.id(), system);
        for (SystemVisualDefinition visual : catalog.systemDefinitions())
            require(systems.containsKey(visual.id()), "Backdrop references unknown star system: " + visual.id());
        for (CelestialVisualDefinition visual : catalog.celestialDefinitions()) {
            StarSystemDefinition system = systems.get(visual.systemId());
            require(system != null, "Celestial visual references unknown star system: " + visual.systemId());
            require(system.bodies().stream().anyMatch(body -> body.id().equals(visual.bodyId())),
                    "Celestial visual references unknown body: " + visual.systemId() + ":" + visual.bodyId());
        }
    }

    private static void validateDistinctness(VisualDefinitionCatalog catalog) {
        Set<String> shipSignatures = new HashSet<>();
        for (ShipVisualDefinition v : catalog.shipDefinitions()) {
            shipSignatures.add(v.preset() + ":" + v.lengthScale() + ":" + v.widthScale() + ":" + v.asymmetry()
                    + ":" + v.armorSections() + ":" + v.engineCount() + ":" + v.engineScale() + ":" + v.engineSpread()
                    + ":" + v.podCount() + ":" + v.hardpointCount() + ":" + v.hardpointLayout()
                    + ":" + v.hangarCount() + ":" + v.antennaCount() + ":" + v.designLanguage() + ":" + v.equipment()
                    + ":" + v.damageDetail() + ":" + v.accentRgb() + ":" + v.glowRgb() + ":" + v.marking());
        }
        require(shipSignatures.size() >= AUTHORED_HULLS.length,
                "Authored functional hulls must have distinct visual signatures.");

        Set<StationVisualPreset> stationPresets = new HashSet<>();
        for (StationVisualDefinition v : catalog.stationDefinitions()) stationPresets.add(v.preset());
        require(stationPresets.size() >= 4, "Major production stations must use distinct structural presets.");

        Set<CelestialVisualClass> celestialClasses = new HashSet<>();
        for (CelestialVisualDefinition v : catalog.celestialDefinitions()) celestialClasses.add(v.visualClass());
        require(celestialClasses.contains(CelestialVisualClass.STAR), "Celestial visuals must include a normal star.");
        require(celestialClasses.contains(CelestialVisualClass.RED_STAR), "Celestial visuals must include a red star.");
        require(celestialClasses.contains(CelestialVisualClass.PULSAR), "Celestial visuals must include a pulsar.");
        require(celestialClasses.contains(CelestialVisualClass.GAS_GIANT), "Celestial visuals must include a gas giant.");
        require(celestialClasses.size() >= 7, "Celestial visuals must exercise several visibly distinct classes.");

        require(new HashSet<>(catalog.systemDefinitions()).size() >= 3, "Several star systems must have distinct backdrop definitions.");
    }

    private static void validateRichControls(VisualDefinitionCatalog catalog) {
        Set<ShipDesignLanguage> languages = new HashSet<>();
        Set<ShipEquipment> equipment = new HashSet<>();
        Set<HardpointLayout> layouts = new HashSet<>();
        boolean hasWear = false;
        for (ShipVisualDefinition v : catalog.shipDefinitions()) {
            languages.add(v.designLanguage()); equipment.add(v.equipment()); layouts.add(v.hardpointLayout());
            hasWear |= v.damageDetail() > 0;
            require(v.engineScale() > 0 && v.engineSpread() >= 0, "Engine art controls must be valid for " + v.id());
        }
        require(languages.size() >= 4, "Ship visuals must exercise multiple design languages.");
        require(equipment.contains(ShipEquipment.MINING) && equipment.contains(ShipEquipment.GAS)
                        && equipment.contains(ShipEquipment.CARGO) && equipment.contains(ShipEquipment.SALVAGE)
                        && equipment.contains(ShipEquipment.CONSTRUCTION),
                "Ship visuals must exercise industrial equipment families.");
        require(layouts.size() >= 3, "Armed hulls must exercise multiple hardpoint layouts.");
        require(hasWear, "At least one authored hull must use damage/wear detail.");
        require(catalog.containsShip("monolith") && catalog.shipDefinitions().stream()
                        .anyMatch(v -> v.id().equals("monolith") && v.preset() == ShipVisualPreset.MONOLITH),
                "Monolith must use its dedicated megastructure silhouette.");

        Set<SystemVfx> vfx = new HashSet<>();
        for (SystemVisualDefinition v : catalog.systemDefinitions()) vfx.add(v.vfx());
        require(vfx.contains(SystemVfx.ION_STREAKS) && vfx.contains(SystemVfx.EMBERS) && vfx.contains(SystemVfx.RADIATION),
                "System visuals must exercise local environmental VFX families.");
    }

    private static void validateHeadlessShipRendering(VisualDefinitionCatalog catalog) {
        for (ShipVisualDefinition visual : catalog.shipDefinitions()) {
            ShipType ship = Rules.findShip(visual.id());
            if (ship == null) continue;
            BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            try {
                g.translate(256, 256);
                ShipVisualRenderer.draw(g, ship, new Color(0x50BEFF));
            } catch (RuntimeException ex) {
                throw new IllegalStateException("Headless ship render failed for " + visual.id() + ": " + ex.getMessage(), ex);
            } finally {
                g.dispose();
            }
        }
    }

    private static void validateMalformedRejection() throws IOException {
        Path temp = Files.createTempFile("starchem-visual-malformed-", ".json");
        try {
            Files.writeString(temp, "{\"version\":1,\"ships\":[{\"id\":\"bad\",\"preset\":\"NOT_A_SHAPE\"}],\"stations\":[],\"celestials\":[],\"systems\":[]}");
            boolean rejected = false;
            try { VisualDefinitionCatalog.loadForValidation(temp); } catch (IllegalStateException expected) { rejected = true; }
            require(rejected, "Malformed visual enum values must be rejected.");
        } finally { Files.deleteIfExists(temp); }
    }

    private static void validateOutOfBoundsRejection() throws IOException {
        Path temp = Files.createTempFile("starchem-visual-invalid-", ".json");
        try {
            Files.writeString(temp, "{\"version\":1,\"ships\":[{\"id\":\"bad\",\"preset\":\"WEDGE\",\"engineScale\":99}],\"stations\":[],\"celestials\":[],\"systems\":[]}");
            boolean rejected = false;
            try { VisualDefinitionCatalog.loadForValidation(temp); } catch (IllegalStateException expected) { rejected = true; }
            require(rejected, "Out-of-bounds visual values must be rejected.");
        } finally { Files.deleteIfExists(temp); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
