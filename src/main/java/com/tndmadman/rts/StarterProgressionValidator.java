package com.tndmadman.rts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic catalog reachability check for the no-inventory Sol Standard
 * bootstrap. This is deliberately an offline validator, never a frame update.
 *
 * Reachability means an authorized, renewable production path exists. Quantity
 * budgets and actual command timing are tested separately; merely possessing
 * a blueprint does not make a hull constructible.
 */
public final class StarterProgressionValidator {
    private StarterProgressionValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem starter progression reachability passed.");
    }

    static void validateOrThrow() {
        Reachable fixed = evaluate(false);
        require(fixed.ships.contains("prospector"), "missing starting Prospector");
        require(fixed.ships.contains("station_builder"), "Deployer cannot be built with starter resources");
        require(fixed.stations.contains("manufacturing"), "Manufacturing Plant cannot be deployed");
        require(fixed.materials.contains(Material.POWER_REGULATOR), "Power Regulators cannot be manufactured");
        require(fixed.materials.contains(Material.RADIATION_SHIELDING), "starter Radiation Shielding unavailable");
        require(fixed.materials.contains(Material.RESEARCH_MATRIX), "Research Matrix cannot be manufactured");
        require(fixed.materials.contains(Material.FUEL), "starter fuel cannot be manufactured");
        require(fixed.stations.contains("laboratory"), "Research Lab is unreachable: check recipe/research grants");
        require(fixed.research.contains("advanced_industry"), "Advanced Industry research is unreachable");
        require(fixed.research.contains("logistics_engineering"), "Logistics Engineering research is unreachable");
        require(fixed.research.contains("autonomous_logistics"), "Autonomous Logistics research is unreachable");
        require(fixed.materials.contains(Material.CARGO_POD), "Cargo Pods cannot be manufactured");
        require(fixed.materials.contains(Material.LOGISTICS_CONTROL_MODULE), "Hauler control modules unavailable");
        require(fixed.stations.contains("shipyard"), "Shipyard package cannot be built");
        require(fixed.ships.contains("hauler"), "Hauler is not actually constructible");

        // Regression fixture recreates the pre-fix cross-system bootstrap loop:
        // Defensive Engineering grants Radiation Shielding, but that research
        // requires a Lab whose package itself consumes Radiation Shielding.
        Reachable broken = evaluate(true);
        require(!broken.stations.contains("laboratory"),
                "historical Radiation Shielding grant-loop fixture unexpectedly reaches a Lab");
        require(!broken.research.contains("advanced_industry"),
                "historical Radiation Shielding grant-loop fixture unexpectedly researches Advanced Industry");
    }

    private static Reachable evaluate(boolean historicalShieldingGate) {
        Set<Material> materials = starterMaterials();
        Set<String> stations = new LinkedHashSet<>();
        stations.add(Rules.DEFAULT_BASE);
        Set<String> ships = new LinkedHashSet<>();
        ships.add(Rules.STARTING_SHIP);
        Set<String> research = new LinkedHashSet<>();

        // Every pass must add something; the upper bound prevents pathological
        // additions from ever turning startup validation into unbounded work.
        for (int pass = 0; pass < 256; pass++) {
            int before = materials.size() + stations.size() + ships.size() + research.size();
            if (stations.contains("manufacturing")) {
                for (CraftableItem recipe : CraftingRules.all()) {
                    if (!inStation(stations, recipe.stationTypes)
                            || !research.containsAll(recipe.requiresResearch)
                            || !unlocked(research, ResearchUnlockKind.CRAFTABLE, recipe.id)
                            || historicalShieldingGate && recipe.id.equals("radiation_shielding")
                                && !research.contains("defensive_engineering")
                            || !hasMaterials(materials, recipe.requiredResources)) continue;
                    materials.add(recipe.outputMaterial);
                }
            }
            for (ShipType ship : Rules.SHIPS.values()) {
                if (ships.contains(ship.id)
                        || !unlocked(research, ResearchUnlockKind.SHIP, ship.id)
                        || !hasMaterials(materials, ship.buildCost)) continue;
                for (String stationId : stations) {
                    BaseType station = Rules.findBase(stationId);
                    if (station != null && station.buildableShips.contains(ship.id)) {
                        ships.add(ship.id);
                        break;
                    }
                }
            }
            for (BaseType station : Rules.BASES.values()) {
                if (stations.contains(station.id)
                        || !unlocked(research, ResearchUnlockKind.STATION_PACKAGE, station.id)
                        || !unlocked(research, ResearchUnlockKind.STATION, station.id)
                        || !hasMaterials(materials, station.buildCost)
                        || ships.stream().noneMatch(id -> {
                            ShipType ship = Rules.findShip(id);
                            return ship != null && ship.baseBuilder;
                        })) continue;
                for (String producerId : stations) {
                    BaseType producer = Rules.findBase(producerId);
                    if (producer != null && producer.basePackages.contains(station.id)) {
                        stations.add(station.id);
                        break;
                    }
                }
            }
            for (ResearchTopic topic : ResearchRules.all()) {
                if (research.contains(topic.id)
                        || !research.containsAll(topic.requires)
                        || !hasMaterials(materials, topic.requiredResources)
                        || !inStation(stations, topic.stationTypes)
                        || excludedByDoctrine(research, topic)) continue;
                boolean fueled = false;
                for (String stationId : topic.stationTypes) {
                    if (!stations.contains(stationId)) continue;
                    StationFuelRequirement requirement = StationFuelRules.requirement(stationId);
                    if (requirement == null || materials.contains(requirement.material())) {
                        fueled = true;
                        break;
                    }
                }
                if (fueled) research.add(topic.id);
            }
            int after = materials.size() + stations.size() + ships.size() + research.size();
            if (before == after) return new Reachable(materials, stations, ships, research);
        }
        throw new IllegalStateException("Starter progression validation failed to converge");
    }

    private static boolean excludedByDoctrine(Set<String> researched, ResearchTopic topic) {
        if (topic.doctrineGroup.isBlank()) return false;
        for (String id : researched) {
            ResearchTopic candidate = ResearchRules.topic(id);
            if (candidate != null && !candidate.id.equals(topic.id)
                    && topic.doctrineGroup.equals(candidate.doctrineGroup)) return true;
        }
        return false;
    }

    private static boolean unlocked(Set<String> research, ResearchUnlockKind kind, String targetId) {
        List<ResearchTopic> grants = ResearchRules.topicsUnlocking(kind, targetId);
        if (grants.isEmpty()) {
            if (kind == ResearchUnlockKind.STATION_PACKAGE) {
                String legacy = StationPackageResearchRules.requiredResearchId(targetId);
                return legacy.isBlank() || research.contains(legacy);
            }
            return true;
        }
        for (ResearchTopic topic : grants) if (research.contains(topic.id)) return true;
        return false;
    }

    private static boolean inStation(Set<String> owned, List<String> allowed) {
        for (String station : allowed) if (owned.contains(station)) return true;
        return false;
    }

    private static boolean hasMaterials(Set<Material> owned, List<Cost> costs) {
        for (Cost cost : costs) if (!owned.contains(cost.material())) return false;
        return true;
    }

    private static Set<Material> starterMaterials() {
        EnumSet<Material> found = EnumSet.noneOf(Material.class);
        Path path = Path.of("config/systems/sol-standard.json");
        try {
            Object parsed = MiniJson.parse(Files.readString(path));
            if (!(parsed instanceof Map<?, ?> root)) throw new IllegalStateException("Invalid starter system JSON");
            addMaterials(found, root.get("spawnMaterials"));
            Object belts = root.get("resourceBelts");
            if (belts instanceof List<?> list) {
                for (Object belt : list) {
                    if (!(belt instanceof Map<?, ?> row)) continue;
                    addMaterials(found, row.get("materials"));
                    Object composition = row.get("composition");
                    if (composition instanceof List<?> entries) {
                        for (Object component : entries) {
                            if (component instanceof Map<?, ?> material) addMaterial(found, material.get("material"));
                        }
                    }
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot inspect starter resource distribution", ex);
        }
        require(found.contains(Material.IRON) && found.contains(Material.COPPER)
                        && found.contains(Material.HYDROGEN),
                "Starter system must expose ore and fuel resources");
        return found;
    }

    private static void addMaterials(Set<Material> found, Object raw) {
        if (raw instanceof List<?> list) for (Object item : list) addMaterial(found, item);
    }

    private static void addMaterial(Set<Material> found, Object value) {
        if (value == null) return;
        Material material = Material.valueOf(String.valueOf(value));
        if (material.raw) found.add(material);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Reachable(Set<Material> materials, Set<String> stations,
                             Set<String> ships, Set<String> research) { }
}
