from pathlib import Path


def replace(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Expected block not found in {path}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


Path("config/galaxy.json").write_text(
    """{
  \"topology\": {
    \"wanderingWormholePairs\": 4
  }
}
""",
    encoding="utf-8",
)

replace(
    "config/starchem.json",
    """      \"config/systems/ancient-graveyard.json\"
    ],
    \"automation\": \"config/automation.json\",""",
    """      \"config/systems/ancient-graveyard.json\"
    ],
    \"galaxy\": \"config/galaxy.json\",
    \"automation\": \"config/automation.json\",""",
)

Path("src/main/java/com/tndmadman/rts/GalaxyTopologyRules.java").write_text(
    """package com.tndmadman.rts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

record GalaxyTopologyRules(int wanderingWormholePairs) {
    static final int DEFAULT_WANDERING_PAIRS = 4;
    static final int MAX_WANDERING_PAIRS = 32;

    GalaxyTopologyRules {
        if (wanderingWormholePairs < 0 || wanderingWormholePairs > MAX_WANDERING_PAIRS) {
            throw new IllegalArgumentException("wanderingWormholePairs must be between 0 and " + MAX_WANDERING_PAIRS + ".");
        }
    }

    static GalaxyTopologyRules load() {
        Path config = configuredPath();
        if (!Files.exists(config)) return new GalaxyTopologyRules(DEFAULT_WANDERING_PAIRS);
        try {
            Map<String,Object> root = object(MiniJson.parse(Files.readString(config)));
            Map<String,Object> topology = object(root.get("topology"));
            Object value = topology.get("wanderingWormholePairs");
            if (value == null) return new GalaxyTopologyRules(DEFAULT_WANDERING_PAIRS);
            if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                    || number.doubleValue() != Math.rint(number.doubleValue())) {
                throw new IllegalArgumentException("wanderingWormholePairs must be an integer.");
            }
            return new GalaxyTopologyRules(number.intValue());
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not load galaxy topology config " + config + ": " + ex.getMessage(), ex);
        }
    }

    private static Path configuredPath() {
        Path manifest = Path.of("config/starchem.json");
        if (!Files.exists(manifest)) return Path.of("config/galaxy.json");
        try {
            Map<String,Object> root = object(MiniJson.parse(Files.readString(manifest)));
            Map<String,Object> files = object(root.get("files"));
            Object path = files.get("galaxy");
            if (path instanceof String text && !text.isBlank()) return Path.of(text.trim());
        } catch (Exception ignored) { }
        return Path.of("config/galaxy.json");
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value) {
        return value instanceof Map<?,?> map ? (Map<String,Object>) map : Map.of();
    }
}
""",
    encoding="utf-8",
)

Path("src/main/java/com/tndmadman/rts/WanderingWormholePlanner.java").write_text(
    """package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class WanderingWormholePlanner {
    private static final int MINIMUM_RING_SEPARATION = 3;
    private static final long SEED_SALT = 0x57414E4445524C4FL;

    private WanderingWormholePlanner() { }

    static List<GalaxyLinkSpec> add(List<GalaxyInstanceSpec> systems, List<GalaxyLinkSpec> fixedLinks,
                                    int requestedPairs, long galaxySeed) {
        List<GalaxyLinkSpec> result = new ArrayList<>(fixedLinks == null ? List.of() : fixedLinks);
        if (requestedPairs <= 0 || systems == null || systems.size() < 4) return List.copyOf(result);

        Set<String> linked = new LinkedHashSet<>();
        Map<String,Integer> degree = new LinkedHashMap<>();
        for (GalaxyInstanceSpec system : systems) degree.put(system.id(), 0);
        for (GalaxyLinkSpec link : result) {
            linked.add(key(link.fromSystemId(), link.toSystemId()));
            degree.computeIfPresent(link.fromSystemId(), (ignored, value) -> value + 1);
            degree.computeIfPresent(link.toSystemId(), (ignored, value) -> value + 1);
        }

        List<Candidate> candidates = candidates(systems, linked, galaxySeed);
        for (int added = 0; added < requestedPairs; added++) {
            Candidate best = candidates.stream().min(candidateOrder(degree)).orElse(null);
            if (best == null) {
                throw new IllegalArgumentException("wanderingWormholePairs requested " + requestedPairs
                        + ", but only " + added + " valid additional pairs are available.");
            }
            result.add(new GalaxyLinkSpec(best.from, best.to));
            linked.add(key(best.from, best.to));
            degree.put(best.from, degree.getOrDefault(best.from, 0) + 1);
            degree.put(best.to, degree.getOrDefault(best.to, 0) + 1);
            candidates.remove(best);
        }
        return List.copyOf(result);
    }

    private static List<Candidate> candidates(List<GalaxyInstanceSpec> systems, Set<String> linked, long seed) {
        List<Candidate> out = new ArrayList<>();
        int count = systems.size();
        for (int i = 0; i < count; i++) {
            for (int j = i + 1; j < count; j++) {
                String from = systems.get(i).id();
                String to = systems.get(j).id();
                int separation = Math.min(j - i, count - (j - i));
                if (separation < MINIMUM_RING_SEPARATION || linked.contains(key(from, to))) continue;
                out.add(new Candidate(from, to, separation, mix(seed ^ SEED_SALT ^ pairHash(from, to))));
            }
        }
        return out;
    }

    private static Comparator<Candidate> candidateOrder(Map<String,Integer> degree) {
        return Comparator
                .comparingInt((Candidate candidate) -> Math.max(degree.getOrDefault(candidate.from, 0), degree.getOrDefault(candidate.to, 0)))
                .thenComparingInt(candidate -> degree.getOrDefault(candidate.from, 0) + degree.getOrDefault(candidate.to, 0))
                .thenComparing(Comparator.comparingInt((Candidate candidate) -> candidate.separation).reversed())
                .thenComparing((Candidate a, Candidate b) -> Long.compareUnsigned(a.tieBreak, b.tieBreak))
                .thenComparing(candidate -> candidate.from)
                .thenComparing(candidate -> candidate.to);
    }

    private static String key(String from, String to) {
        return from.compareTo(to) <= 0 ? from + "->" + to : to + "->" + from;
    }

    private static long pairHash(String from, String to) {
        String a = from.compareTo(to) <= 0 ? from : to;
        String b = from.compareTo(to) <= 0 ? to : from;
        return ((long)a.hashCode() << 32) ^ (b.hashCode() & 0xFFFFFFFFL);
    }

    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        return value ^ value >>> 33;
    }

    private record Candidate(String from, String to, int separation, long tieBreak) { }
}
""",
    encoding="utf-8",
)

Path("src/main/java/com/tndmadman/rts/GalaxyPlan.java").write_text(
    """package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

record GalaxyInstanceSpec(
        String id,
        String templateId,
        SystemLifetime lifetime,
        String initialControllerId
) { }

record GalaxyLinkSpec(String fromSystemId, String toSystemId) { }

record GalaxyPlan(
        int copiesPerTemplate,
        String entrySystemId,
        List<GalaxyInstanceSpec> systems,
        List<GalaxyLinkSpec> links
) { }

final class GalaxyPlanner {
    private GalaxyPlanner() { }

    static GalaxyPlan standard(String primaryTemplateId, int requestedCopies) {
        return standard(primaryTemplateId, requestedCopies, 0L, GalaxyTopologyRules.load());
    }

    static GalaxyPlan standard(String primaryTemplateId, int requestedCopies, long galaxySeed) {
        return standard(primaryTemplateId, requestedCopies, galaxySeed, GalaxyTopologyRules.load());
    }

    static GalaxyPlan standard(String primaryTemplateId, int requestedCopies, long galaxySeed,
                               GalaxyTopologyRules topology) {
        int copies = Math.max(1, Math.min(2, requestedCopies));
        List<StarSystemDefinition> templates = orderedTemplates(primaryTemplateId);
        List<GalaxyInstanceSpec> systems = new ArrayList<>();
        for (int copy = 1; copy <= copies; copy++) {
            for (StarSystemDefinition template : templates) {
                String id = copy == 1 ? template.id() : template.id() + "_" + copy;
                String initialController = StarSystems.CORSAIR_SYSTEM_ID.equals(template.id()) ? Config.CORSAIRS_ID : "";
                systems.add(new GalaxyInstanceSpec(id, template.id(), SystemLifetime.STATIC, initialController));
            }
        }

        List<GalaxyLinkSpec> fixedLinks = connectedLinks(systems);
        int wanderingPairs = topology == null ? GalaxyTopologyRules.DEFAULT_WANDERING_PAIRS : topology.wanderingWormholePairs();
        List<GalaxyLinkSpec> links = WanderingWormholePlanner.add(systems, fixedLinks, wanderingPairs, galaxySeed);
        String entry = systems.isEmpty() ? StarSystems.DEFAULT_SYSTEM_ID : systems.get(0).id();
        return new GalaxyPlan(copies, entry, List.copyOf(systems), links);
    }

    private static List<StarSystemDefinition> orderedTemplates(String primaryTemplateId) {
        List<StarSystemDefinition> templates = new ArrayList<>(StarSystems.staticOptions());
        templates.sort((a, b) -> {
            boolean ap = a.id().equals(primaryTemplateId);
            boolean bp = b.id().equals(primaryTemplateId);
            if (ap != bp) return ap ? -1 : 1;
            return a.id().compareTo(b.id());
        });
        return templates;
    }

    private static List<GalaxyLinkSpec> connectedLinks(List<GalaxyInstanceSpec> systems) {
        if (systems.size() < 2) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        List<GalaxyLinkSpec> links = new ArrayList<>();
        for (int i = 0; i < systems.size(); i++) addLink(links, seen, systems.get(i).id(), systems.get((i + 1) % systems.size()).id());
        for (int i = 0; i < systems.size(); i += 4) addLink(links, seen, systems.get(i).id(), systems.get((i + Math.min(4, systems.size() - 1)) % systems.size()).id());
        return List.copyOf(links);
    }

    private static void addLink(List<GalaxyLinkSpec> links, Set<String> seen, String from, String to) {
        if (from == null || to == null || from.equals(to)) return;
        String a = from.compareTo(to) <= 0 ? from : to;
        String b = from.compareTo(to) <= 0 ? to : from;
        if (seen.add(a + "->" + b)) links.add(new GalaxyLinkSpec(a, b));
    }
}
""",
    encoding="utf-8",
)

replace(
    "src/main/java/com/tndmadman/rts/GalaxyCoordinator.java",
    "GalaxyPlan plan = GalaxyPlanner.standard(primary.id(), GalaxyRuntimeOptions.copiesPerTemplate());",
    "GalaxyPlan plan = GalaxyPlanner.standard(primary.id(), GalaxyRuntimeOptions.copiesPerTemplate(), seed);",
)

replace(
    "src/main/java/com/tndmadman/rts/World.java",
    """    void update(double dt) { SystemAudio.listenTo(this); update(dt, true); }
    void updateCurrentSystem(double dt) { double step = SystemSimulationScheduler.step(this, dt); if (step > 0) update(step, false); }
    private void update(double dt, boolean updateInactiveSystems) { updateEnvironment(dt); SystemModifierRules.applyEnvironment(this, dt); resourceRespawnSystem.update(this, dt); StationFuelRules.consume(this, dt); logisticsSystem.update(this, dt); itemPickupSystem.update(this); scoutSystem.update(this); npcSystemForActiveSystem().update(this, dt); npcGalaxyDirector.update(this, dt); for (Unit unit : new ArrayList<>(units.values())) updateUnit(unit, dt); transferTouchingShips(); weaponSystem.update(this, dt); cleanupDestroyed(); saveActiveSystem(); if (updateInactiveSystems) updateInactiveSystems(dt); }""",
    """    void update(double dt) { SystemAudio.listenTo(this); updateEnvironment(dt); updateSimulation(dt); updateInactiveSystems(dt); }
    void updateCurrentSystem(double dt) { if (dt <= 0) return; updateEnvironment(dt); double step = SystemSimulationScheduler.step(this, dt); if (step > 0) updateSimulation(step); else saveActiveSystem(); }
    private void updateSimulation(double dt) { SystemModifierRules.applyEnvironment(this, dt); resourceRespawnSystem.update(this, dt); StationFuelRules.consume(this, dt); logisticsSystem.update(this, dt); itemPickupSystem.update(this); scoutSystem.update(this); npcSystemForActiveSystem().update(this, dt); npcGalaxyDirector.update(this, dt); for (Unit unit : new ArrayList<>(units.values())) updateUnit(unit, dt); transferTouchingShips(); weaponSystem.update(this, dt); cleanupDestroyed(); saveActiveSystem(); }""",
)

replace(
    "src/main/java/com/tndmadman/rts/RulesValidator.java",
    """            validateNpcFactions(files, shipIds, stationIds, researchIds, craftableIds);
            validateSystems(files);

            return List.copyOf(errors);""",
    """            validateNpcFactions(files, shipIds, stationIds, researchIds, craftableIds);
            validateSystems(files);
            validateGalaxy(files);

            return List.copyOf(errors);""",
)

replace(
    "src/main/java/com/tndmadman/rts/RulesValidator.java",
    """        private void validateBodies(Object value, String context) {""",
    """        private void validateGalaxy(Map<String,Object> files) {
            Object galaxyFile = files.get("galaxy");
            if (galaxyFile == null) {
                errors.add("manifest.files.galaxy is missing.");
                return;
            }
            for (String rawPath : filePaths(galaxyFile, "manifest.files.galaxy")) {
                Map<String,Object> galaxy = readObject(Path.of(rawPath), "galaxy config " + rawPath);
                Map<String,Object> topology = object(galaxy.get("topology"));
                Object value = topology.get("wanderingWormholePairs");
                if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                        || number.doubleValue() != Math.rint(number.doubleValue())) {
                    errors.add("galaxy config " + rawPath + ".topology.wanderingWormholePairs must be an integer.");
                } else if (number.intValue() < 0 || number.intValue() > GalaxyTopologyRules.MAX_WANDERING_PAIRS) {
                    errors.add("galaxy config " + rawPath + ".topology.wanderingWormholePairs must be between 0 and "
                            + GalaxyTopologyRules.MAX_WANDERING_PAIRS + ".");
                }
            }
        }

        private void validateBodies(Object value, String context) {""",
)

replace(
    "src/main/java/com/tndmadman/rts/GalaxyConnectivityValidator.java",
    """            validateStaticPlan(1);
            validateStaticPlan(2);
            validateSoloBootstrap();""",
    """            validateStaticPlan(1);
            validateStaticPlan(2);
            validateWanderingTopology();
            validateSoloBootstrap();""",
)

replace(
    "src/main/java/com/tndmadman/rts/GalaxyConnectivityValidator.java",
    """    private static void validateSoloBootstrap() {""",
    """    private static void validateWanderingTopology() {
        GalaxyPlan base = GalaxyPlanner.standard(StarSystems.DEFAULT_SYSTEM_ID, 1, 8675309L, new GalaxyTopologyRules(0));
        GalaxyPlan added = GalaxyPlanner.standard(StarSystems.DEFAULT_SYSTEM_ID, 1, 8675309L, new GalaxyTopologyRules(4));
        GalaxyPlan repeated = GalaxyPlanner.standard(StarSystems.DEFAULT_SYSTEM_ID, 1, 8675309L, new GalaxyTopologyRules(4));
        require(added.links().size() == base.links().size() + 4,
                "configured wandering wormhole pair count was not added exactly");
        require(linkKeys(added).equals(linkKeys(repeated)),
                "wandering wormholes are not deterministic for the same galaxy seed");
        require(linkKeys(added).size() == added.links().size(),
                "wandering wormholes created a duplicate link");
    }

    private static Set<String> linkKeys(GalaxyPlan plan) {
        Set<String> keys = new HashSet<>();
        for (GalaxyLinkSpec link : plan.links()) {
            String a = link.fromSystemId().compareTo(link.toSystemId()) <= 0 ? link.fromSystemId() : link.toSystemId();
            String b = link.fromSystemId().compareTo(link.toSystemId()) <= 0 ? link.toSystemId() : link.fromSystemId();
            keys.add(a + "->" + b);
        }
        return keys;
    }

    private static void validateSoloBootstrap() {""",
)

replace(
    "src/main/java/com/tndmadman/rts/SystemSimulationSchedulerValidator.java",
    """        World warm = new World("Warm", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);""",
    """        World orbit = new World("Orbit", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
        ResourceNode orbiting = orbit.resources.get(0);
        double beforeAngle = orbiting.orbitAngle;
        orbit.updateCurrentSystem(0.05);
        require(Math.abs(orbiting.orbitAngle - beforeAngle) > 0.000001,
                "cold-system resource orbit was frozen by simulation throttling");

        World warm = new World("Warm", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);""",
)
