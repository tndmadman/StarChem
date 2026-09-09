from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def text(path):
    return (ROOT / path).read_text()


def write(path, value):
    (ROOT / path).write_text(value)


def replace_once(path, old, new):
    value = text(path)
    if new in value:
        return
    if old not in value:
        raise RuntimeError(f"Expected block not found in {path}: {old[:100]!r}")
    write(path, value.replace(old, new, 1))


def regex_once(path, pattern, replacement, flags=re.S):
    value = text(path)
    updated, count = re.subn(pattern, replacement, value, count=1, flags=flags)
    if count != 1:
        raise RuntimeError(f"Expected one regex match in {path}, found {count}: {pattern[:100]!r}")
    write(path, updated)


# GalaxyPreview: expose the deterministic strict candidate sequence so runtime home placement
# uses exactly the same permanent-topology rules as preview.
regex_once(
    "src/main/java/com/tndmadman/rts/GalaxyPreview.java",
    r"    static List<String> startRegions\(GalaxyPlan plan, int requestedStarts, int minimumSeparation\) \{.*?\n    \}\n\n    private static Map<String,Set<String>> graph",
    '''    static List<String> startRegions(GalaxyPlan plan, int requestedStarts, int minimumSeparation) {
        if (plan == null || plan.systems().isEmpty() || requestedStarts <= 0) return List.of();
        int required = Math.min(requestedStarts, plan.systems().size());
        List<String> candidates = startRegionCandidates(plan, minimumSeparation);
        if (candidates.size() < required) {
            throw new IllegalArgumentException("Galaxy cannot place " + requestedStarts
                    + " start regions at graph separation " + minimumSeparation + ".");
        }
        return List.copyOf(candidates.subList(0, required));
    }

    static List<String> startRegionCandidates(GalaxyPlan plan, int minimumSeparation) {
        if (plan == null || plan.systems().isEmpty()) return List.of();
        int separation = Math.max(1, minimumSeparation);
        Map<String,Set<String>> graph = graph(plan);
        List<String> candidates = plan.systems().stream().map(GalaxyInstanceSpec::id).toList();
        List<String> selected = new ArrayList<>();
        String entry = plan.entrySystemId();
        if (entry == null || entry.isBlank() || !graph.containsKey(entry)) entry = candidates.get(0);
        selected.add(entry);

        while (selected.size() < candidates.size()) {
            String best = null;
            int bestDistance = -1;
            for (String candidate : candidates) {
                if (selected.contains(candidate)) continue;
                int nearest = Integer.MAX_VALUE;
                for (String existing : selected) nearest = Math.min(nearest, distance(graph, candidate, existing));
                if (nearest > bestDistance || nearest == bestDistance && (best == null || candidate.compareTo(best) < 0)) {
                    best = candidate;
                    bestDistance = nearest;
                }
            }
            if (best == null || bestDistance < separation) break;
            selected.add(best);
        }
        return List.copyOf(selected);
    }

    private static Map<String,Set<String>> graph''')

# GalaxySystemIdentity: generated copies beyond _2 should display distinctly as well.
replace_once(
    "src/main/java/com/tndmadman/rts/GalaxySystemIdentity.java",
    '''    static boolean playerHome(String systemId) {
        return systemId != null && systemId.startsWith(StarSystems.PLAYER_HOME_SYSTEM_ID + "_");
    }

    private static boolean matchesGeneratedId''',
    '''    static boolean playerHome(String systemId) {
        return systemId != null && systemId.startsWith(StarSystems.PLAYER_HOME_SYSTEM_ID + "_");
    }

    static String displayName(String systemId, StarSystemDefinition definition) {
        if (definition == null) return systemId == null ? "" : systemId;
        int copy = generatedCopyNumber(systemId, definition.id());
        return copy <= 1 ? definition.name() : definition.name() + " " + roman(copy);
    }

    private static int generatedCopyNumber(String systemId, String templateId) {
        if (systemId == null || templateId == null || systemId.equals(templateId)) return 1;
        String prefix = templateId + "_";
        if (!systemId.startsWith(prefix)) return 1;
        try {
            int copy = Integer.parseInt(systemId.substring(prefix.length()));
            return copy >= 2 ? copy : 1;
        } catch (NumberFormatException ex) {
            return 1;
        }
    }

    private static String roman(int value) {
        if (value <= 1) return "";
        int[] numbers = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] numerals = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder out = new StringBuilder();
        int remaining = value;
        for (int i = 0; i < numbers.length; i++) {
            while (remaining >= numbers[i]) {
                out.append(numerals[i]);
                remaining -= numbers[i];
            }
        }
        return out.toString();
    }

    private static boolean matchesGeneratedId''')

# GalaxyCoordinator: persist and apply deterministic separated start regions.
replace_once(
    "src/main/java/com/tndmadman/rts/GalaxyCoordinator.java",
    '''    private final Map<String, WorldSystemState> systems = new LinkedHashMap<>();
    private final Map<String, String> playerHomes = new LinkedHashMap<>();
    private String activeSystemId;
    private String entrySystemId;
    private long seed;
    private int nextResourceId = 1;''',
    '''    private final Map<String, WorldSystemState> systems = new LinkedHashMap<>();
    private final Map<String, String> playerHomes = new LinkedHashMap<>();
    private final Map<String, String> playerStartRegions = new LinkedHashMap<>();
    private List<String> startRegionCandidates = List.of();
    private boolean proceduralStartRegions;
    private String activeSystemId;
    private String entrySystemId;
    private long seed;
    private int nextResourceId = 1;''')

replace_once(
    "src/main/java/com/tndmadman/rts/GalaxyCoordinator.java",
    '''        this.seed = seed;
        systems.clear();
        playerHomes.clear();
        clearWorld(world);
        nextResourceId = 1;

        GalaxyPlan plan = GalaxyPlanner.standard(primary.id(), GalaxyRuntimeOptions.copiesPerTemplate(), seed);
        entrySystemId = plan.entrySystemId();''',
    '''        this.seed = seed;
        systems.clear();
        playerHomes.clear();
        playerStartRegions.clear();
        startRegionCandidates = List.of();
        proceduralStartRegions = false;
        clearWorld(world);
        nextResourceId = 1;

        GalaxyPlan plan = GalaxyPlanner.standard(primary.id(), GalaxyRuntimeOptions.copiesPerTemplate(), seed);
        GalaxyGenerationSettings generation = GalaxyRuntimeOptions.generationSettings();
        proceduralStartRegions = generation.procedural();
        if (proceduralStartRegions) {
            startRegionCandidates = GalaxyPreview.startRegionCandidates(plan, generation.startingSeparation());
        }
        entrySystemId = plan.entrySystemId();''')

replace_once(
    "src/main/java/com/tndmadman/rts/GalaxyCoordinator.java",
    '''        out.put("nextResourceId", nextResourceId);
        out.put("playerHomes", new LinkedHashMap<>(playerHomes));
        List<Object> savedSystems = new ArrayList<>();''',
    '''        out.put("nextResourceId", nextResourceId);
        out.put("playerHomes", new LinkedHashMap<>(playerHomes));
        out.put("playerStartRegions", new LinkedHashMap<>(playerStartRegions));
        out.put("startRegionCandidates", new ArrayList<>(startRegionCandidates));
        out.put("proceduralStartRegions", proceduralStartRegions);
        List<Object> savedSystems = new ArrayList<>();''')

replace_once(
    "src/main/java/com/tndmadman/rts/GalaxyCoordinator.java",
    '''        systems.clear();
        playerHomes.clear();
        clearWorld(world);
        seed = ServerSaveStore.longValue(save, "seed", System.nanoTime() ^ System.currentTimeMillis());
        entrySystemId = ServerSaveStore.string(save, "entrySystemId", "");
        activeSystemId = ServerSaveStore.string(save, "activeSystemId", "");
        nextResourceId = Math.max(1, ServerSaveStore.intValue(save, "nextResourceId", 1));
        Map<String,Object> homes = ServerSaveStore.object(save.get("playerHomes"));
        for (Map.Entry<String,Object> entry : homes.entrySet()) playerHomes.put(entry.getKey(), ServerSaveStore.asString(entry.getValue(), ""));
        for (Object item : ServerSaveStore.list(save.get("systems"))) restoreSystem(world, ServerSaveStore.object(item));
        if (activeSystemId == null || activeSystemId.isBlank() || !systems.containsKey(activeSystemId)) activeSystemId = fallbackActiveSystemId();''',
    '''        systems.clear();
        playerHomes.clear();
        playerStartRegions.clear();
        startRegionCandidates = List.of();
        proceduralStartRegions = false;
        clearWorld(world);
        seed = ServerSaveStore.longValue(save, "seed", System.nanoTime() ^ System.currentTimeMillis());
        entrySystemId = ServerSaveStore.string(save, "entrySystemId", "");
        activeSystemId = ServerSaveStore.string(save, "activeSystemId", "");
        nextResourceId = Math.max(1, ServerSaveStore.intValue(save, "nextResourceId", 1));
        Map<String,Object> homes = ServerSaveStore.object(save.get("playerHomes"));
        for (Map.Entry<String,Object> entry : homes.entrySet()) playerHomes.put(entry.getKey(), ServerSaveStore.asString(entry.getValue(), ""));
        Map<String,Object> starts = ServerSaveStore.object(save.get("playerStartRegions"));
        for (Map.Entry<String,Object> entry : starts.entrySet()) playerStartRegions.put(entry.getKey(), ServerSaveStore.asString(entry.getValue(), ""));
        List<String> restoredCandidates = new ArrayList<>();
        for (Object item : ServerSaveStore.list(save.get("startRegionCandidates"))) {
            String id = ServerSaveStore.asString(item, "");
            if (!id.isBlank()) restoredCandidates.add(id);
        }
        startRegionCandidates = List.copyOf(restoredCandidates);
        proceduralStartRegions = ServerSaveStore.boolValue(save, "proceduralStartRegions", false);
        for (Object item : ServerSaveStore.list(save.get("systems"))) restoreSystem(world, ServerSaveStore.object(item));
        playerHomes.entrySet().removeIf(entry -> !systems.containsKey(entry.getValue()));
        playerStartRegions.entrySet().removeIf(entry -> !systems.containsKey(entry.getValue()));
        if (proceduralStartRegions) {
            List<String> validCandidates = new ArrayList<>();
            for (String id : startRegionCandidates) {
                WorldSystemState state = systems.get(id);
                if (state != null && state.isStatic()) validCandidates.add(id);
            }
            if (validCandidates.isEmpty()) {
                for (WorldSystemState state : systems.values()) if (state.isStatic()) validCandidates.add(state.id);
            }
            startRegionCandidates = List.copyOf(validCandidates);
        } else startRegionCandidates = List.of();
        if (activeSystemId == null || activeSystemId.isBlank() || !systems.containsKey(activeSystemId)) activeSystemId = fallbackActiveSystemId();''')

replace_once(
    "src/main/java/com/tndmadman/rts/GalaxyCoordinator.java",
    '''    private String displayName(WorldSystemState state) {
        return state.id.endsWith("_2") ? state.definition.name() + " II" : state.definition.name();
    }''',
    '''    private String displayName(WorldSystemState state) {
        return GalaxySystemIdentity.displayName(state.id, state.definition);
    }''')

replace_once(
    "src/main/java/com/tndmadman/rts/GalaxyCoordinator.java",
    '''        home.control.protect(playerId);
        playerHomes.put(playerId, home.id);
        connectHome(world, home);
        return asGalaxySystem(home);
    }

    private void connectHome(World world, WorldSystemState home) {
        WorldSystemState entry = systems.get(entrySystemId);
        if (entry == null) entry = systems.get(fallbackActiveSystemId());
        link(world, home, entry);
        WorldSystemState second = secondStaticSystem(entry == null ? "" : entry.id);
        if (second != null) link(world, home, second);
    }

    private WorldSystemState secondStaticSystem(String excludedId) {
        for (WorldSystemState state : systems.values()) {
            if (state.isStatic() && !state.id.equals(excludedId)) return state;
        }
        return null;
    }

    String playerHomeSystemId(World world, String playerId, StarSystemDefinition primary) { return ensurePlayerHome(world, playerId, primary).id; }''',
    '''        home.control.protect(playerId);
        playerHomes.put(playerId, home.id);
        connectHome(world, home, assignStartRegion(playerId));
        return asGalaxySystem(home);
    }

    private String assignStartRegion(String playerId) {
        String existing = playerStartRegions.get(playerId);
        WorldSystemState existingState = systems.get(existing);
        if (existingState != null && existingState.isStatic()) return existing;
        if (!proceduralStartRegions) return "";

        Set<String> used = new LinkedHashSet<>(playerStartRegions.values());
        for (String candidate : startRegionCandidates) {
            WorldSystemState state = systems.get(candidate);
            if (state != null && state.isStatic() && !used.contains(candidate)) {
                playerStartRegions.put(playerId, candidate);
                return candidate;
            }
        }

        String fallback = mostSeparatedStaticSystem(used);
        if (fallback.isBlank()) fallback = entrySystemId == null ? "" : entrySystemId;
        if (!fallback.isBlank()) playerStartRegions.put(playerId, fallback);
        return fallback;
    }

    private String mostSeparatedStaticSystem(Set<String> used) {
        String best = "";
        int bestDistance = -1;
        for (WorldSystemState candidate : systems.values()) {
            if (candidate == null || !candidate.isStatic() || used.contains(candidate.id)) continue;
            int nearest = Integer.MAX_VALUE;
            for (String selected : used) nearest = Math.min(nearest, staticDistance(candidate.id, selected));
            if (used.isEmpty()) nearest = candidate.id.equals(entrySystemId) ? Integer.MAX_VALUE : 0;
            if (nearest > bestDistance || nearest == bestDistance && (best.isBlank() || candidate.id.compareTo(best) < 0)) {
                best = candidate.id;
                bestDistance = nearest;
            }
        }
        return best;
    }

    private int staticDistance(String from, String to) {
        if (from == null || to == null || from.isBlank() || to.isBlank()) return Integer.MAX_VALUE / 4;
        if (from.equals(to)) return 0;
        Set<String> visited = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        Map<String,Integer> distance = new HashMap<>();
        queue.add(from);
        distance.put(from, 0);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!visited.add(current)) continue;
            WorldSystemState state = systems.get(current);
            if (state == null || !state.isStatic()) continue;
            int nextDistance = distance.getOrDefault(current, 0) + 1;
            for (WormholeGate gate : state.wormholes) {
                WorldSystemState next = systems.get(gate.toSystemId);
                if (next == null || !next.isStatic()) continue;
                if (next.id.equals(to)) return nextDistance;
                if (!visited.contains(next.id)) {
                    distance.putIfAbsent(next.id, nextDistance);
                    queue.addLast(next.id);
                }
            }
        }
        return Integer.MAX_VALUE / 4;
    }

    private void connectHome(World world, WorldSystemState home, String startRegionId) {
        WorldSystemState anchor = systems.get(startRegionId);
        if (anchor == null || !anchor.isStatic()) anchor = systems.get(entrySystemId);
        if (anchor == null) anchor = systems.get(fallbackActiveSystemId());
        link(world, home, anchor);
        WorldSystemState second = secondStaticNeighbor(anchor);
        if (second == null) second = secondStaticSystem(anchor == null ? "" : anchor.id);
        if (second != null) link(world, home, second);
    }

    private WorldSystemState secondStaticNeighbor(WorldSystemState anchor) {
        if (anchor == null) return null;
        for (WormholeGate gate : anchor.wormholes) {
            WorldSystemState candidate = systems.get(gate.toSystemId);
            if (candidate != null && candidate.isStatic()) return candidate;
        }
        return null;
    }

    private WorldSystemState secondStaticSystem(String excludedId) {
        for (WorldSystemState state : systems.values()) {
            if (state.isStatic() && !state.id.equals(excludedId)) return state;
        }
        return null;
    }

    String playerStartRegionSystemId(String playerId) { return playerStartRegions.getOrDefault(playerId, ""); }
    String playerHomeSystemId(World world, String playerId, StarSystemDefinition primary) { return ensurePlayerHome(world, playerId, primary).id; }''')

replace_once(
    "src/main/java/com/tndmadman/rts/GalaxyCoordinator.java",
    '''        playerHomes.remove(playerId);
        return pruneAbandonedSystemsAfterSave(world);''',
    '''        playerHomes.remove(playerId);
        playerStartRegions.remove(playerId);
        return pruneAbandonedSystemsAfterSave(world);''')

replace_once(
    "src/main/java/com/tndmadman/rts/GalaxyCoordinator.java",
    '''        playerHomes.values().removeIf(deleted::contains);
        for (WorldSystemState state : systems.values()) state.wormholes.removeIf(gate -> deleted.contains(gate.toSystemId));''',
    '''        playerHomes.values().removeIf(deleted::contains);
        playerStartRegions.values().removeIf(deleted::contains);
        for (WorldSystemState state : systems.values()) state.wormholes.removeIf(gate -> deleted.contains(gate.toSystemId));''')

# World: use the bounded round-robin scheduler on procedural maps and expose tiny validation hooks.
replace_once(
    "src/main/java/com/tndmadman/rts/World.java",
    '''    private final GalaxyCoordinator galaxy = new GalaxyCoordinator();''',
    '''    private final GalaxyCoordinator galaxy = new GalaxyCoordinator();
    private final GalaxyInactiveSimulationScheduler proceduralInactiveScheduler = new GalaxyInactiveSimulationScheduler();''')

replace_once(
    "src/main/java/com/tndmadman/rts/World.java",
    '''    String playerHomeSystemId(String playerId) { return galaxy.playerHomeSystemId(this, playerId, starSystem); }''',
    '''    String playerHomeSystemId(String playerId) { return galaxy.playerHomeSystemId(this, playerId, starSystem); }
    String playerStartRegionSystemId(String playerId) { return galaxy.playerStartRegionSystemId(playerId); }''')

replace_once(
    "src/main/java/com/tndmadman/rts/World.java",
    '''    void restoreServerSaveGalaxy(Map<String,Object> save) { celestials = galaxy.restoreSave(this, save); systemTime = galaxy.activeSystemTime(); selectedResourceId = -1; }''',
    '''    void restoreServerSaveGalaxy(Map<String,Object> save) { proceduralInactiveScheduler.reset(); celestials = galaxy.restoreSave(this, save); systemTime = galaxy.activeSystemTime(); selectedResourceId = -1; }''')

replace_once(
    "src/main/java/com/tndmadman/rts/World.java",
    '''    private void setSystemSeed(long seed) { systemSeed = seed; systemTime = 0; random = new Random(seed); clearNpcAiRuntimeState(); SimulationCadence.clear(this); LogisticsRouteSystem.clear(this); remoteGalaxyMapSnapshot = null; celestials = galaxy.rebuild(this, starSystem, seed); }''',
    '''    private void setSystemSeed(long seed) { systemSeed = seed; systemTime = 0; random = new Random(seed); proceduralInactiveScheduler.reset(); clearNpcAiRuntimeState(); SimulationCadence.clear(this); LogisticsRouteSystem.clear(this); remoteGalaxyMapSnapshot = null; celestials = galaxy.rebuild(this, starSystem, seed); }''')

regex_once(
    "src/main/java/com/tndmadman/rts/World.java",
    r"    private void updateInactiveSystems\(double dt\) \{.*?\n    \}\n\n    private void updateUnit",
    '''    private void updateInactiveSystems(double dt) {
        if (dt == 0) return;
        if (GalaxyRuntimeOptions.generationSettings().procedural()) {
            proceduralInactiveScheduler.update(this, dt, authoritativeGalaxyMapSnapshot());
            return;
        }
        String previousSystemId = activeSystemId();
        String previousStatus = status;
        GalaxyMapSnapshot snapshot = galaxyMapSnapshot();
        if (snapshot.empty()) return;
        try {
            for (GalaxyMapSystem system : snapshot.systems()) {
                if (system == null || system.id() == null || system.id().isBlank() || system.id().equals(previousSystemId)) continue;
                activateSystem(system.id());
                updateCurrentSystem(dt);
            }
        } finally {
            if (previousSystemId != null && !previousSystemId.isBlank()) activateSystem(previousSystemId);
            status = previousStatus;
        }
    }

    int proceduralInactiveSystemsUpdatedLastFrame() { return proceduralInactiveScheduler.lastBatchSize(); }

    private void updateUnit''')

# Lobby: preserve compact presets, add a separate advanced-control dialog.
replace_once(
    "src/main/java/com/tndmadman/rts/LobbyPanel.java",
    '''    private final JButton galaxyPreviewButton = new MenuButton("PREVIEW GALAXY");''',
    '''    private final JButton galaxyPreviewButton = new MenuButton("PREVIEW GALAXY");
    private final JButton galaxyAdvancedButton = new MenuButton("ADVANCED GALAXY");
    private GalaxyGenerationSettings galaxyAdvancedSettings =
            GalaxyMatchSetup.procedural(GalaxySizePreset.MEDIUM, GalaxyTopologyStyle.MIXED);''')

replace_once(
    "src/main/java/com/tndmadman/rts/LobbyPanel.java",
    '''        proceduralGalaxyBox.addActionListener(e -> updateGalaxySetupControls());
        galaxyPreviewButton.addActionListener(e -> previewGalaxy());''',
    '''        proceduralGalaxyBox.addActionListener(e -> updateGalaxySetupControls());
        galaxyPreviewButton.addActionListener(e -> previewGalaxy());
        galaxyAdvancedButton.addActionListener(e -> editAdvancedGalaxySettings());''')

replace_once(
    "src/main/java/com/tndmadman/rts/LobbyPanel.java",
    '''        addFormRow(grid, row++, "Solo galaxy seed", galaxySeedField);
        addFormRow(grid, row++, "Galaxy preview", galaxyPreviewButton);''',
    '''        addFormRow(grid, row++, "Solo galaxy seed", galaxySeedField);
        addFormRow(grid, row++, "Advanced generation", galaxyAdvancedButton);
        addFormRow(grid, row++, "Galaxy preview", galaxyPreviewButton);''')

replace_once(
    "src/main/java/com/tndmadman/rts/LobbyPanel.java",
    '''    private GalaxyGenerationSettings selectedGalaxyGenerationSettings() {
        return GalaxyMatchSetup.procedural(selectedGalaxySize(), selectedGalaxyTopology());
    }''',
    '''    private GalaxyGenerationSettings selectedGalaxyGenerationSettings() {
        GalaxyGenerationSettings tuned = galaxyAdvancedSettings == null
                ? GalaxyMatchSetup.procedural(selectedGalaxySize(), selectedGalaxyTopology()) : galaxyAdvancedSettings;
        return new GalaxyGenerationSettings(true, selectedGalaxySize().systemCount(), selectedGalaxyTopology(),
                tuned.permanentConnectivityDensity(), tuned.frontierFrequency(), tuned.resourceRichness(),
                tuned.rareResourceFrequency(), tuned.hazardFrequency(), tuned.npcDensity(), tuned.startingSeparation(),
                tuned.templateWeights());
    }

    private void editAdvancedGalaxySettings() {
        GalaxyGenerationSettings edited = GalaxyAdvancedSetup.edit(this, selectedGalaxyGenerationSettings());
        if (edited == null) return;
        galaxyAdvancedSettings = edited;
        setStatus("Updated advanced procedural galaxy settings.");
    }''')

replace_once(
    "src/main/java/com/tndmadman/rts/LobbyPanel.java",
    '''        galaxySeedField.setEnabled(procedural);
        galaxyPreviewButton.setEnabled(procedural);''',
    '''        galaxySeedField.setEnabled(procedural);
        galaxyAdvancedButton.setEnabled(procedural);
        galaxyPreviewButton.setEnabled(procedural);''')

# Validator: close the remaining acceptance gaps with live start placement, full-state persistence,
# malformed settings, and bounded inactive simulation.
replace_once(
    "src/main/java/com/tndmadman/rts/Issue383GalaxyGenerationValidator.java",
    '''            validatePreviewAndStarts();
            validateBounds();
            validatePersistence();
            validateMultiplayerGenerationSync();''',
    '''            validatePreviewAndStarts();
            validateRuntimeStartRegions();
            validateBounds();
            validateMalformedSettings();
            validateBoundedInactiveSimulation();
            validatePersistence();
            validateMultiplayerGenerationSync();''')

replace_once(
    "src/main/java/com/tndmadman/rts/Issue383GalaxyGenerationValidator.java",
    '''    private static void validateBounds() {''',
    '''    private static void validateRuntimeStartRegions() {
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

    private static void validateBounds() {''')

replace_once(
    "src/main/java/com/tndmadman/rts/Issue383GalaxyGenerationValidator.java",
    '''    private static void validatePersistence() {''',
    '''    private static void validateMalformedSettings() {
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

    private static void validatePersistence() {''')

regex_once(
    "src/main/java/com/tndmadman/rts/Issue383GalaxyGenerationValidator.java",
    r"    private static void validatePersistence\(\) \{.*?\n    \}\n\n    private static void validateMultiplayerGenerationSync",
    '''    private static void validatePersistence() {
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

    private static void validateMultiplayerGenerationSync''')

replace_once(
    "src/main/java/com/tndmadman/rts/Issue383GalaxyGenerationValidator.java",
    '''    private static String planSignature(GalaxyPlan plan) {''',
    '''    private static boolean linked(GalaxyMapSnapshot snapshot, String a, String b) {
        if (snapshot == null || a == null || b == null) return false;
        for (GalaxyMapLink link : snapshot.links()) {
            if (a.equals(link.fromSystemId()) && b.equals(link.toSystemId())
                    || b.equals(link.fromSystemId()) && a.equals(link.toSystemId())) return true;
        }
        return false;
    }

    private static String planSignature(GalaxyPlan plan) {''')

print("Issue 383 finish patch applied.")
