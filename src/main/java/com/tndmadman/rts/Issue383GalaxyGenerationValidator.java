package com.tndmadman.rts;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Issue383GalaxyGenerationValidator {
    private Issue383GalaxyGenerationValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem issue 383 procedural galaxy validation passed.");
    }

    static void validateOrThrow() {
        try {
            validateDeterminism();
            validateTopologyStyles();
            validateStableGeneratedIds();
            validatePreviewAndStarts();
            validateRuntimeStartRegions();
            validateBounds();
            validateMalformedSettings();
            validateBoundedInactiveSimulation();
            validatePersistence();
            validateMultiplayerGenerationSync();
        } finally {
            GalaxyRuntimeOptions.configureCopies(1);
        }
    }

    private static void validateDeterminism() {
        GalaxyGenerationSettings settings = settings(24, GalaxyTopologyStyle.MIXED, 0.45, 0.20, 2);
        GalaxyPlan first = GalaxyPlanner.procedural(StarSystems.DEFAULT_SYSTEM_ID, settings, 0x383L,
                new GalaxyTopologyRules(0));
        GalaxyPlan repeated = GalaxyPlanner.procedural(StarSystems.DEFAULT_SYSTEM_ID, settings, 0x383L,
                new GalaxyTopologyRules(0));
        GalaxyPlan different = GalaxyPlanner.procedural(StarSystems.DEFAULT_SYSTEM_ID, settings, 0x384L,
                new GalaxyTopologyRules(0));

        require(planSignature(first).equals(planSignature(repeated)),
                "same settings and seed did not reproduce the same permanent galaxy");
        require(!planSignature(first).equals(planSignature(different)),
                "different seeds did not materially change the procedural galaxy");
    }

    private static void validateTopologyStyles() {
        long seed = 0x383000L;
        for (GalaxyTopologyStyle style : GalaxyTopologyStyle.values()) {
            GalaxyGenerationSettings settings = settings(32, style, 0.55, 0.30, 2);
            GalaxyPlan plan = GalaxyPlanner.procedural(StarSystems.DEFAULT_SYSTEM_ID, settings, seed + style.ordinal(),
                    new GalaxyTopologyRules(0));
            require(plan.systems().size() == 32, style + " did not create the requested system count");
            require(connected(plan), style + " produced a disconnected galaxy");
            require(noDuplicateOrSelfLinks(plan), style + " produced duplicate or self links");
            require(plan.links().size() <= 192, style + " exceeded the permanent-link safety bound");
        }
    }

    private static void validateStableGeneratedIds() {
        GalaxyGenerationSettings settings = settings(64, GalaxyTopologyStyle.CLUSTERED, 0.35, 0.20, 2);
        GalaxyPlan plan = GalaxyPlanner.procedural(StarSystems.DEFAULT_SYSTEM_ID, settings, 77L,
                new GalaxyTopologyRules(0));
        Set<String> ids = new HashSet<>();
        boolean sawNumberedCopy = false;
        for (GalaxyInstanceSpec system : plan.systems()) {
            require(ids.add(system.id()), "generated duplicate system id " + system.id());
            require(system.templateId().equals(GalaxySystemIdentity.templateId(system.id())),
                    "generated system id did not resolve to its template: " + system.id());
            if (!system.id().equals(system.templateId())) sawNumberedCopy = true;
        }
        require(sawNumberedCopy, "large procedural galaxy did not exercise numbered template copies");
    }

    private static void validatePreviewAndStarts() {
        GalaxyGenerationSettings settings = settings(24, GalaxyTopologyStyle.FRONTIER, 0.18, 0.35, 2);
        GalaxyPreview preview = GalaxyPreview.generate(StarSystems.DEFAULT_SYSTEM_ID, settings, 383L, 2);
        require(preview.snapshot().systems().size() == settings.targetSystemCount(),
                "preview did not reflect generated system count");
        require(preview.snapshot().systems().stream().allMatch(system -> system.ships() == 0 && system.bases() == 0
                        && system.resources() == 0 && system.controllerId().isBlank()),
                "preview leaked runtime galaxy state");
        require(preview.startRegionSystemIds().size() == 2,
                "preview did not produce the requested start regions");
    }

    private static void validateRuntimeStartRegions() {
        GalaxyGenerationSettings settings = settings(32, GalaxyTopologyStyle.MIXED, 0.32, 0.20, 3);
        long generationSeed = 383_3003L;
        GalaxyPreview expected = GalaxyPreview.generate(StarSystems.DEFAULT_SYSTEM_ID, settings, generationSeed, 3);
        GalaxyRuntimeOptions.configureGeneration(settings, generationSeed);
        PlayerRegistry.reset("WAIT", "Issue 383 Starts", 0x50BEFF);
        World world = new World("Issue 383 Starts", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        for (int i = 1; i <= 3; i++) {
            String playerId = "P" + i;
            PlayerRegistry.register(playerId, "Start " + i, 0x50BEFF + i, false);
            world.spawnPlayerGroup(playerId, i, i == 1);
            String assigned = world.playerStartRegionSystemId(playerId);
            require(expected.startRegionSystemIds().get(i - 1).equals(assigned),
                    "live player home did not use deterministic separated start region for " + playerId);
            require(linked(world.authoritativeGalaxyMapSnapshot(), world.playerHomeSystemId(playerId), assigned),
                    "player home was not linked to its assigned procedural start region for " + playerId);
        }
    }

    private static void validateBounds() {
        boolean rejected = false;
        try {
            settings(GalaxyGenerationSettings.MAX_SYSTEMS + 1, GalaxyTopologyStyle.MIXED, 0.5, 0.2, 2);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(rejected, "oversized procedural galaxy settings were accepted");

        GalaxyGenerationSettings huge = settings(GalaxyGenerationSettings.MAX_SYSTEMS,
                GalaxyTopologyStyle.DENSE, 1.0, 0.0, 2);
        GalaxyPlan plan = GalaxyPlanner.procedural(StarSystems.DEFAULT_SYSTEM_ID, huge, 999L,
                new GalaxyTopologyRules(0));
        require(plan.systems().size() == GalaxyGenerationSettings.MAX_SYSTEMS,
                "maximum supported galaxy size was not generated exactly");
        require(plan.links().size() <= 192, "maximum supported galaxy exceeded link bound");
        require(connected(plan), "maximum supported galaxy was disconnected");
    }

    private static void validateMalformedSettings() {
        boolean nonFiniteRejected = false;
        try {
            new GalaxyGenerationSettings(true, 24, GalaxyTopologyStyle.MIXED, Double.NaN, 0.2,
                    1.0, 0.15, 0.15, 0.5, 3, Map.of());
        } catch (IllegalArgumentException expected) {
            nonFiniteRejected = true;
        }
        require(nonFiniteRejected, "non-finite procedural settings were accepted");

        boolean unknownTemplateRejected = false;
        try {
            new GalaxyGenerationSettings(true, 24, GalaxyTopologyStyle.MIXED, 0.35, 0.2,
                    1.0, 0.15, 0.15, 0.5, 3, Map.of("not-a-real-system", 1.0));
        } catch (IllegalArgumentException expected) {
            unknownTemplateRejected = true;
        }
        require(unknownTemplateRejected, "unknown procedural template weight was accepted");
    }

    private static void validateBoundedInactiveSimulation() {
        GalaxyGenerationSettings settings = settings(GalaxyGenerationSettings.MAX_SYSTEMS,
                GalaxyTopologyStyle.DENSE, 0.85, 0.10, 2);
        GalaxyRuntimeOptions.configureGeneration(settings, 383_6400L);
        PlayerRegistry.reset("WAIT", "Issue 383 Bounded", 0x50BEFF);
        World world = new World("Issue 383 Bounded", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        world.update(0.016);
        int updated = world.proceduralInactiveSystemsUpdatedLastFrame();
        require(updated > 0, "procedural inactive scheduler did not advance any inactive system");
        require(updated <= GalaxyInactiveSimulationScheduler.MAX_SYSTEMS_PER_FRAME,
                "procedural map simulated too many inactive systems in one frame: " + updated);
        require(world.npcRuntimeSystemCount() <= GalaxyInactiveSimulationScheduler.MAX_SYSTEMS_PER_FRAME + 1,
                "procedural map initialized full-galaxy NPC simulation in one frame");
    }

    private static void validatePersistence() {
        GalaxyGenerationSettings settings = settings(24, GalaxyTopologyStyle.HUBS, 0.38, 0.20, 2);
        GalaxyRuntimeOptions.configureGeneration(settings, 383_9001L);
        PlayerRegistry.reset("WAIT", "Issue 383 Save Source", 0x50BEFF);
        World source = new World("Issue 383 Save Source", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(source);
        PlayerRegistry.register("P1", "Persisted Player", 0x50BEFF, false);
        source.spawnPlayerGroup("P1", 7, true);
        String home = source.playerHomeSystemId("P1");
        source.activateSystem(home);
        Base base = source.bases.values().stream().filter(candidate -> "P1".equals(candidate.playerId)).findFirst().orElseThrow();
        base.inventory.put(Material.IRON, 383.25);
        if (!source.resources.isEmpty()) source.resources.get(0).amount = Math.max(0, source.resources.get(0).amount - 17.5);
        source.saveActiveSystem();
        Map<String,Object> saved = source.captureServerSaveGalaxy();

        GalaxyGenerationSettings differentSettings = settings(14, GalaxyTopologyStyle.FRONTIER, 0.10, 0.45, 2);
        GalaxyRuntimeOptions.configureGeneration(differentSettings, 999_383L);
        World restored = new World("Issue 383 Save Restore", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(restored);
        restored.restoreServerSaveGalaxy(saved);
        Map<String,Object> recaptured = restored.captureServerSaveGalaxy();

        require(saved.equals(recaptured),
                "procedural save/restore did not preserve full concrete galaxy control and dynamic state");
        require(source.playerStartRegionSystemId("P1").equals(restored.playerStartRegionSystemId("P1")),
                "procedural save/restore did not preserve the player's assigned start region");
    }

    private static void validateMultiplayerGenerationSync() {
        GalaxyGenerationSettings settings = settings(24, GalaxyTopologyStyle.CLUSTERED, 0.42, 0.20, 2);
        long generationSeed = 383_2026L;
        GalaxyRuntimeOptions.configureGeneration(settings, generationSeed);
        PlayerRegistry.reset("WAIT", "Issue 383 Server", 0x50BEFF);
        World server = new World("Issue 383 Server", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(server);
        GalaxyMapSnapshot serverMap = server.authoritativeGalaxyMapSnapshot();
        String packet = GalaxyMapWire.encode(1, serverMap);
        require(packet.contains("|G,"), "procedural server galaxy packet omitted generation descriptor");
        long serverWorldSeed = server.systemSeed();
        String serverSignature = snapshotSignature(serverMap);

        GalaxyRuntimeOptions.configureGeneration(GalaxyGenerationSettings.legacy(1), 0L);
        PlayerRegistry.reset("WAIT", "Issue 383 Client", 0x50BEFF);
        World client = new World("Issue 383 Client", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(client);
        GalaxyMapWire.decode(packet);

        require(client.systemSeed() == serverWorldSeed,
                "procedural client did not adopt the authoritative server world seed");
        require(GalaxyRuntimeOptions.generationSettings().equals(settings),
                "procedural client did not adopt the authoritative server generation settings");
        require(GalaxyRuntimeOptions.generationSeed(client.systemSeed()) == generationSeed,
                "procedural client did not adopt the authoritative server generation seed");
        require(snapshotSignature(client.authoritativeGalaxyMapSnapshot()).equals(serverSignature),
                "procedural client permanent topology does not match the authoritative server");
    }

    private static GalaxyGenerationSettings settings(int count, GalaxyTopologyStyle style,
                                                     double connectivity, double frontier,
                                                     int startSeparation) {
        return new GalaxyGenerationSettings(true, count, style, connectivity, frontier,
                1.0, 0.15, 0.15, 0.5, startSeparation, Map.of());
    }

    private static boolean connected(GalaxyPlan plan) {
        if (plan.systems().isEmpty()) return true;
        Map<String,Set<String>> graph = new HashMap<>();
        for (GalaxyInstanceSpec system : plan.systems()) graph.put(system.id(), new HashSet<>());
        for (GalaxyLinkSpec link : plan.links()) {
            Set<String> from = graph.get(link.fromSystemId());
            Set<String> to = graph.get(link.toSystemId());
            if (from == null || to == null) return false;
            from.add(link.toSystemId());
            to.add(link.fromSystemId());
        }
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(plan.systems().get(0).id());
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!visited.add(current)) continue;
            queue.addAll(graph.getOrDefault(current, Set.of()));
        }
        return visited.size() == graph.size();
    }

    private static boolean noDuplicateOrSelfLinks(GalaxyPlan plan) {
        Set<String> seen = new HashSet<>();
        for (GalaxyLinkSpec link : plan.links()) {
            if (link.fromSystemId().equals(link.toSystemId())) return false;
            String a = link.fromSystemId().compareTo(link.toSystemId()) <= 0 ? link.fromSystemId() : link.toSystemId();
            String b = link.fromSystemId().compareTo(link.toSystemId()) <= 0 ? link.toSystemId() : link.fromSystemId();
            if (!seen.add(a + "->" + b)) return false;
        }
        return true;
    }

    private static boolean linked(GalaxyMapSnapshot snapshot, String a, String b) {
        if (snapshot == null || a == null || b == null) return false;
        for (GalaxyMapLink link : snapshot.links()) {
            if (a.equals(link.fromSystemId()) && b.equals(link.toSystemId())
                    || b.equals(link.fromSystemId()) && a.equals(link.toSystemId())) return true;
        }
        return false;
    }

    private static String planSignature(GalaxyPlan plan) {
        StringBuilder out = new StringBuilder();
        for (GalaxyInstanceSpec system : plan.systems()) {
            out.append(system.id()).append(':').append(system.templateId()).append(';');
        }
        out.append('|');
        plan.links().stream()
                .map(link -> link.fromSystemId().compareTo(link.toSystemId()) <= 0
                        ? link.fromSystemId() + "->" + link.toSystemId()
                        : link.toSystemId() + "->" + link.fromSystemId())
                .sorted()
                .forEach(link -> out.append(link).append(';'));
        return out.toString();
    }

    private static String snapshotSignature(GalaxyMapSnapshot snapshot) {
        StringBuilder out = new StringBuilder();
        snapshot.systems().stream()
                .map(system -> system.id() + ':' + system.templateId())
                .sorted()
                .forEach(system -> out.append(system).append(';'));
        out.append('|');
        snapshot.links().stream()
                .map(link -> link.fromSystemId().compareTo(link.toSystemId()) <= 0
                        ? link.fromSystemId() + "->" + link.toSystemId()
                        : link.toSystemId() + "->" + link.fromSystemId())
                .sorted()
                .forEach(link -> out.append(link).append(';'));
        return out.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
