package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.List;

final class World {
    int width;
    int height;
    final String localPlayerId = "SOLO";
    final String localPlayerName;
    final Color localColor = new Color(0x50BEFF);
    final List<ResourceNode> resources = new ArrayList<>();
    final Map<String, Unit> units = new LinkedHashMap<>();
    final Map<String, Base> bases = new LinkedHashMap<>();
    final List<ProjectileShot> shots = new ArrayList<>();
    final List<ExplosionEffect> explosions = new ArrayList<>();
    final List<WorldItem> items = new ArrayList<>();
    final List<WormholeGate> wormholes = new ArrayList<>();
    final EnumMap<Material, Double> stockpile = new EnumMap<>(Material.class);
    final Map<String, Set<String>> completedResearch = new LinkedHashMap<>();
    final LogisticsSystem logisticsSystem = new LogisticsSystem();
    final AiDevSettings aiDevSettings = new AiDevSettings();
    private final Set<String> devFreeBuildPlayers = new LinkedHashSet<>();
    private final GalaxyCoordinator galaxy = new GalaxyCoordinator();
    private final Set<String> disabledNpcFactionIds;
    private final Map<String, NpcSystem> npcSystems = new LinkedHashMap<>();
    private final Map<String, NpcSystem> organizedNpcSystems = new LinkedHashMap<>();
    private final Map<String, NpcFactionRuntime> npcFactionRuntimes = new LinkedHashMap<>();
    private final NpcGalaxyDirector npcGalaxyDirector = new NpcGalaxyDirector();
    private GalaxyMapSnapshot remoteGalaxyMapSnapshot;
    boolean devFreeBuild;
    private StarSystemDefinition starSystem;
    private long systemSeed;
    double systemTime;
    private Random random;
    private CelestialSystem celestials;
    private final WorkSystem workSystem = new WorkSystem();
    private final HaulerSystem haulerSystem = new HaulerSystem();
    private final ScoutSystem scoutSystem = new ScoutSystem();
    private final ResourceRespawnSystem resourceRespawnSystem = new ResourceRespawnSystem();
    private final BuildSystem buildSystem = new BuildSystem();
    private final WeaponSystem weaponSystem = new WeaponSystem();
    private final ItemPickupSystem itemPickupSystem = new ItemPickupSystem();
    private int nextUnitId = 1;
    private int nextBaseId = 1;
    private int nextShotId = 1;
    int nextWorldItemId = 1;
    int selectedResourceId = -1;
    String status = "Right-click a resource with a ship selected to auto-harvest.";

    World(String localPlayerName) { this(localPlayerName, Set.of(), StarSystems.DEFAULT_SYSTEM_ID); }
    World(String localPlayerName, Set<String> disabledNpcFactionIds) { this(localPlayerName, disabledNpcFactionIds, StarSystems.DEFAULT_SYSTEM_ID); }

    World(String localPlayerName, Set<String> disabledNpcFactionIds, String systemId) {
        this(localPlayerName, disabledNpcFactionIds, systemId, true);
    }

    World(String localPlayerName, Set<String> disabledNpcFactionIds, String systemId, boolean spawnLocalPlayer) {
        this.localPlayerName = Config.clean(localPlayerName);
        this.disabledNpcFactionIds = disabledNpcFactionIds == null ? Set.of() : Set.copyOf(disabledNpcFactionIds);
        setStarSystem(systemId);
        setSystemSeed(System.nanoTime() ^ System.currentTimeMillis());
        if (spawnLocalPlayer) {
            ensurePlayerHome(localPlayerId, true);
            activateSystem(playerHomeSystemId(localPlayerId));
            Point2D basePoint = startPointForPlayer(localPlayerId, 0);
            addBase(Rules.DEFAULT_BASE, basePoint.getX(), basePoint.getY());
            Point2D start = startShipPoint(basePoint);
            spawnShip(Rules.STARTING_SHIP, start.getX(), start.getY());
        }
        saveActiveSystem();
        SystemAudio.listenTo(this);
        status = spawnLocalPlayer
                ? "Entered " + starSystem.name() + ". Left-click a wormhole to view that system; ships travel on contact."
                : "Waiting for player assignment in " + starSystem.name() + ".";
    }

    long systemSeed() { return systemSeed; }
    double systemTime() { return systemTime; }
    String systemId() { return starSystem.id(); }
    String systemName() { return starSystem.name(); }
    String activeSystemId() { return galaxy.activeSystemId(); }
    String activeSystemControllerId() { return galaxy.activeControllerId(); }
    GalaxyMapSnapshot galaxyMapSnapshot() {
        GalaxyMapSnapshot snapshot = remoteGalaxyMapSnapshot;
        return snapshot == null ? galaxy.mapSnapshot(this) : GalaxyMapWire.withActive(snapshot, activeSystemId());
    }
    GalaxyMapSnapshot authoritativeGalaxyMapSnapshot() { return galaxy.mapSnapshot(this); }
    void applyRemoteGalaxyMapSnapshot(GalaxyMapSnapshot snapshot) { remoteGalaxyMapSnapshot = snapshot; }
    void configureGalaxyCopies(int copies) {
        int normalized = Math.max(1, Math.min(2, copies));
        if (GalaxyRuntimeOptions.copiesPerTemplate() == normalized) return;
        GalaxyRuntimeOptions.configureCopies(normalized);
        clearNpcAiRuntimeState();
        remoteGalaxyMapSnapshot = null;
        celestials = galaxy.rebuild(this, starSystem, systemSeed);
        systemTime = galaxy.activeSystemTime();
    }
    boolean viewGalaxySystem(String systemId) { boolean viewed = galaxy.viewSystem(this, systemId); celestials = galaxy.activeCelestials(); systemTime = galaxy.activeSystemTime(); selectedResourceId = -1; if (viewed) SystemAudio.listenTo(this); return viewed; }
    void advanceClientEnvironment(double dt) {
        if (!Double.isFinite(dt) || dt <= 0) return;
        galaxy.advanceVisual(dt);
        celestials = galaxy.activeCelestials();
        systemTime = galaxy.activeSystemTime();
    }
    void syncClientEnvironment(String systemId, double hostTime) {
        if (systemId == null || systemId.isBlank() || !Double.isFinite(hostTime) || hostTime < 0) return;
        galaxy.syncVisual(this, systemId, hostTime);
        celestials = galaxy.activeCelestials();
        systemTime = galaxy.activeSystemTime();
    }
    boolean hasLiveAssets(String playerId) { return galaxy.hasLiveAssets(this, playerId); }
    String playerHomeSystemId(String playerId) { return galaxy.playerHomeSystemId(this, playerId, starSystem); }
    void activateSystem(String systemId) { celestials = galaxy.activate(this, systemId); systemTime = galaxy.activeSystemTime(); }
    void saveActiveSystem() { galaxy.saveActive(this); }
    Map<String,Object> captureServerSaveGalaxy() { return galaxy.captureSave(this); }
    void restoreServerSaveGalaxy(Map<String,Object> save) { celestials = galaxy.restoreSave(this, save); systemTime = galaxy.activeSystemTime(); selectedResourceId = -1; }
    Map<String,Object> captureServerSaveRuntime() {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("simulationScheduler", SystemSimulationScheduler.capture(this));
        out.put("productionPlanner", ProductionPlanner.capture(this));
        out.put("npcFactions", captureNpcFactionRuntimes());
        out.put("npcStrategicDirector", NpcStrategicDirector.capture(this));
        out.put("npcStationConstruction", NpcStationConstructionSystem.capture(this));
        out.put("npcExpeditions", NpcExpeditionSystem.capture(this));
        out.put("npcExpeditionReadiness", NpcExpeditionReadinessSystem.capture(this));
        out.put("npcRecovery", NpcRecoverySystem.capture(this));
        out.put("npcRepairEvacuation", NpcRepairEvacuationSystem.capture(this));
        out.put("npcSquadCombat", NpcSquadCombatSystem.capture(this));
        return out;
    }
    void restoreServerSaveRuntime(Map<String,Object> save) {
        Map<String,Object> data = save == null ? Map.of() : save;
        SystemSimulationScheduler.restore(this, data.get("simulationScheduler"));
        ProductionPlanner.restore(this, data.get("productionPlanner"));
        restoreNpcFactionRuntimes(data.get("npcFactions"));
        NpcStrategicDirector.restore(this, data.get("npcStrategicDirector"));
        NpcStationConstructionSystem.restore(this, data.get("npcStationConstruction"));
        NpcExpeditionSystem.restore(this, data.get("npcExpeditions"));
        NpcExpeditionReadinessSystem.restore(this, data.get("npcExpeditionReadiness"));
        NpcRecoverySystem.restore(this, data.get("npcRecovery"));
        NpcRepairEvacuationSystem.restore(this, data.get("npcRepairEvacuation"));
        NpcSquadCombatSystem.restore(this, data.get("npcSquadCombat"));
    }
    List<Material> spawnMaterials() { return starSystem.spawnMaterials(); }
    List<Material> spawnMaterials(String playerId) { return galaxy.spawnMaterials(this, playerId, starSystem); }
    void ensurePlayerHome(String playerId) { galaxy.ensurePlayerHome(this, playerId, starSystem); }
    void ensurePlayerHome(String playerId, boolean usePrimaryDefinition) { galaxy.ensurePlayerHome(this, playerId, starSystem, usePrimaryDefinition); }
    Point2D startPointForPlayer(String playerId, int slot) { return galaxy.startPoint(this, playerId, slot, starSystem); }
    Point2D npcSpawnPoint(String factionId, double padding) { return galaxy.npcSpawnPoint(this, factionId, padding); }
    void movePlayerAssetsToSystem(String playerId, String targetSystemId) { galaxy.moveAssetsToSystem(this, playerId, targetSystemId); celestials = galaxy.activeCelestials(); systemTime = galaxy.activeSystemTime(); }
    boolean launchNpcExpedition(String factionId, String targetSystemId, int combatShips) { return galaxy.moveNpcExpedition(this, factionId, targetSystemId, combatShips); }

    Set<String> removePlayerAndPruneEmptySystems(String playerId) {
        completedResearch.remove(playerId);
        Set<String> deleted = galaxy.removePlayerAndPruneEmptySystems(this, playerId);
        npcSystems.keySet().removeAll(deleted);
        removeOrganizedNpcSystems(deleted);
        SystemSimulationScheduler.removeSystems(this, deleted);
        celestials = galaxy.activeCelestials();
        systemTime = galaxy.activeSystemTime();
        selectedResourceId = -1;
        return deleted;
    }

    Set<String> pruneEmptyDynamicSystems() {
        Set<String> deleted = galaxy.pruneAbandonedSystems(this);
        npcSystems.keySet().removeAll(deleted);
        removeOrganizedNpcSystems(deleted);
        SystemSimulationScheduler.removeSystems(this, deleted);
        celestials = galaxy.activeCelestials();
        systemTime = galaxy.activeSystemTime();
        return deleted;
    }

    String wormholeTargetAt(double x, double y) {
        WormholeGate gate = wormholeAt(x, y);
        return gate == null ? "" : gate.toSystemId;
    }

    boolean viewSystemThroughWormhole(String targetSystemId) {
        WormholeGate gate = wormholeTo(targetSystemId);
        if (gate == null) return false;
        boolean viewed = galaxy.viewThrough(this, gate);
        celestials = galaxy.activeCelestials();
        systemTime = galaxy.activeSystemTime();
        selectedResourceId = -1;
        if (viewed) SystemAudio.listenTo(this);
        return viewed;
    }

    boolean jumpThroughWormholeAt(double x, double y) {
        WormholeGate gate = wormholeAt(x, y);
        if (gate == null) return false;
        boolean viewed = galaxy.viewThrough(this, gate);
        celestials = galaxy.activeCelestials();
        systemTime = galaxy.activeSystemTime();
        selectedResourceId = -1;
        if (viewed) SystemAudio.listenTo(this);
        return viewed;
    }

    boolean transferTouchingShips() {
        String sourceSystemId = activeSystemId();
        Set<String> destinations = wormholeDestinationsTouching("");
        boolean moved = galaxy.transferTouchingShips(this);
        celestials = galaxy.activeCelestials();
        systemTime = galaxy.activeSystemTime();
        if (moved) {
            WormholeTransitNotice.play(this, sourceSystemId);
            for (String destination : destinations) WormholeTransitNotice.incoming(destination);
        }
        return moved;
    }

    boolean transferTouchingShips(String playerId) {
        String sourceSystemId = activeSystemId();
        Set<String> destinations = wormholeDestinationsTouching(playerId);
        boolean moved = galaxy.transferTouchingShips(this, playerId);
        celestials = galaxy.activeCelestials();
        systemTime = galaxy.activeSystemTime();
        if (moved) {
            if (PlayerRegistry.isLocal(playerId)) WormholeTransitNotice.play(this, sourceSystemId);
            for (String destination : destinations) WormholeTransitNotice.incoming(destination);
        }
        return moved;
    }

    private Set<String> wormholeDestinationsTouching(String playerId) {
        Set<String> destinations = new LinkedHashSet<>();
        boolean allPlayers = playerId == null || playerId.isBlank();
        for (Unit unit : units.values()) {
            if (!allPlayers && !playerId.equals(unit.playerId)) continue;
            if (unit.wormholeCooldown > 0 || ProductionSystem.refitReserved(this, unit.key())
                    || ShipModuleRules.tackled(this, unit)) continue;
            WormholeGate gate = wormholeAt(unit.x, unit.y);
            if (gate != null && gate.toSystemId != null && !gate.toSystemId.isBlank()) destinations.add(gate.toSystemId);
        }
        return destinations;
    }

    boolean playerShipTouchingWormhole(String playerId) { if (playerId == null || playerId.isBlank()) return false; for (Unit unit : units.values()) if (playerId.equals(unit.playerId) && unit.wormholeCooldown <= 0 && !ProductionSystem.refitReserved(this, unit.key()) && !ShipModuleRules.tackled(this, unit) && wormholeAt(unit.x, unit.y) != null) return true; return false; }
    private WormholeGate wormholeAt(double x, double y) { for (WormholeGate gate : wormholes) if (gate.contains(x, y)) return gate; return null; }
    private WormholeGate wormholeTo(String targetSystemId) { if (targetSystemId == null || targetSystemId.isBlank()) return null; for (WormholeGate gate : wormholes) if (targetSystemId.equals(gate.toSystemId)) return gate; return null; }

    void spawnPlayerGroup(String playerId, int slot) { spawnPlayerGroup(playerId, slot, false); }

    void spawnPlayerGroup(String playerId, int slot, boolean usePrimaryDefinition) {
        GalaxySystem home = galaxy.ensurePlayerHome(this, playerId, starSystem, usePrimaryDefinition);
        String previous = activeSystemId();
        celestials = galaxy.activate(this, home.id);
        systemTime = galaxy.activeSystemTime();
        Point2D bp = startPointForPlayer(playerId, slot);
        Point2D sp = startShipPoint(bp);
        int baseId = nextBaseNumber(playerId);
        int unitId = nextUnitNumber(playerId);
        bases.put(playerId + ":B" + baseId, new Base(playerId + ":B" + baseId, playerId, Rules.DEFAULT_BASE, bp.getX(), bp.getY()));
        units.put(Unit.key(playerId, unitId), new Unit(playerId, unitId, Rules.STARTING_SHIP, sp.getX(), sp.getY()));
        galaxy.saveActive(this);
        if (previous != null && !previous.isBlank() && !previous.equals(home.id)) {
            celestials = galaxy.activate(this, previous);
            systemTime = galaxy.activeSystemTime();
        }
    }

    void setDevFreeBuild(String playerId, boolean enabled) { if (playerId == null || playerId.isBlank()) return; if (enabled) devFreeBuildPlayers.add(playerId); else devFreeBuildPlayers.remove(playerId); if (PlayerRegistry.isLocal(playerId)) devFreeBuild = enabled; }
    boolean devFreeBuildFor(String playerId) { return playerId != null && devFreeBuildPlayers.contains(playerId); }
    boolean hasResearch(String playerId, String topicId) { return playerId != null && topicId != null && completedResearch.getOrDefault(playerId, Set.of()).contains(topicId); }
    void completeResearch(String playerId, String topicId) { if (playerId == null || playerId.isBlank() || topicId == null || topicId.isBlank()) return; completedResearch.computeIfAbsent(playerId, ignored -> new LinkedHashSet<>()).add(topicId); }
    void useSystemSeed(long seed) { if (seed != systemSeed) syncEnvironment(systemId(), seed, 0); }
    void syncEnvironment(long seed, double hostTime) { syncEnvironment(systemId(), seed, hostTime); }
    void syncEnvironment(String newSystemId, long seed, double hostTime) { boolean changed = !StarSystems.get(newSystemId).id().equals(systemId()); if (changed) setStarSystem(newSystemId); if (changed || seed != systemSeed) setSystemSeed(seed); double delta = hostTime - systemTime; if (Math.abs(delta) > 0.02) advanceEnvironment(delta); else { systemTime = hostTime; galaxy.setActiveSystemTime(hostTime); } }
    private void setStarSystem(String systemId) { starSystem = StarSystems.get(systemId); }
    private void setSystemSeed(long seed) { systemSeed = seed; systemTime = 0; random = new Random(seed); clearNpcAiRuntimeState(); remoteGalaxyMapSnapshot = null; celestials = galaxy.rebuild(this, starSystem, seed); }
    private Point2D startShipPoint(Point2D basePoint) { return new Point2D.Double(basePoint.getX() + 180, basePoint.getY() - 80); }

    private void clearNpcAiRuntimeState() {
        npcSystems.clear();
        organizedNpcSystems.clear();
        npcFactionRuntimes.clear();
        NpcStrategicDirector.clear(this);
        NpcRecoverySystem.clear(this);
        NpcRepairEvacuationSystem.clear(this);
        NpcExpeditionSystem.clear(this);
        NpcStationConstructionSystem.clear(this);
        NpcSquadCombatSystem.clear(this);
    }

    private List<Object> captureNpcFactionRuntimes() {
        List<Object> out = new ArrayList<>();
        for (NpcFactionRuntime runtime : npcFactionRuntimes.values()) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("factionId", runtime.factionId());
            row.put("homeSystemId", runtime.homeSystemId());
            row.put("state", runtime.state().name());
            row.put("spawnTimer", runtime.spawnTimer());
            row.put("spawnCount", runtime.spawnCount());
            out.add(row);
        }
        return out;
    }

    private void restoreNpcFactionRuntimes(Object saved) {
        npcFactionRuntimes.clear();
        for (Object item : ServerSaveStore.list(saved)) {
            Map<String,Object> row = ServerSaveStore.object(item);
            String factionId = ServerSaveStore.string(row, "factionId", "");
            NpcFaction faction = null;
            for (NpcFaction candidate : NpcRules.factions()) {
                if (candidate.id().equals(factionId)) {
                    faction = candidate;
                    break;
                }
            }
            if (faction == null || faction.behavior() != NpcBehavior.FACTION) continue;
            NpcFactionRuntime runtime = new NpcFactionRuntime(faction);
            runtime.restore(
                    ServerSaveStore.enumValue(NpcFactionRuntime.State.class, row.get("state"), NpcFactionRuntime.State.INITIALIZING),
                    ServerSaveStore.doubleValue(row, "spawnTimer", runtime.spawnTimer()),
                    ServerSaveStore.intValue(row, "spawnCount", runtime.spawnCount()));
            npcFactionRuntimes.put(faction.id(), runtime);
        }
    }

    void resetOrganizedNpcFactionState(NpcFaction faction, boolean defeated) {
        resetOrganizedNpcFactionState(faction,
                defeated ? NpcFactionResetReason.DEFEATED
                        : NpcFactionResetReason.SPAWN_PREP);
    }

    void resetOrganizedNpcFactionState(NpcFaction faction,
                                       NpcFactionResetReason reason) {
        if (faction == null || faction.behavior() != NpcBehavior.FACTION) return;
        NpcFactionResetReason normalized = reason == null
                ? NpcFactionResetReason.SPAWN_PREP : reason;
        String suffix = "|" + faction.id();
        organizedNpcSystems.keySet().removeIf(key -> key.endsWith(suffix));
        NpcFactionScopedRuntimeReset.clearExpedition(this, faction, normalized);
        NpcStationConstructionSystem.clearFaction(this, faction, normalized);
        NpcRecoverySystem.clearFaction(this, faction);
        NpcFactionScopedRuntimeReset.clearSquads(this, faction);
        if (normalized == NpcFactionResetReason.DEFEATED) {
            NpcStrategicDirector.onDefeated(this, faction);
        }
    }

    void updateEnvironment(double dt) { advanceEnvironment(dt); updateItems(dt); updateExplosions(dt); }
    private void advanceEnvironment(double dt) { galaxy.update(this, dt); systemTime = galaxy.activeSystemTime(); celestials = galaxy.activeCelestials(); ResourceSpawner.update(resources, celestials, dt); ResourceNetDebug.worldTick(this, dt); }
    private void updateItems(double dt) { Iterator<WorldItem> it = items.iterator(); while (it.hasNext()) { WorldItem item = it.next(); item.update(dt, width, height); if (item.empty()) it.remove(); } }
    void update(double dt) { SystemAudio.listenTo(this); updateEnvironment(dt); updateSimulation(dt); updateInactiveSystems(dt); }
    void updateCurrentSystem(double dt) { if (dt <= 0) return; updateEnvironment(dt); double step = SystemSimulationScheduler.step(this, dt); if (step > 0) updateSimulation(step); else saveActiveSystem(); }
    private void updateSimulation(double dt) {
        SystemModifierRules.applyEnvironment(this, dt);
        resourceRespawnSystem.update(this, dt);
        StationFuelRules.consume(this, dt);
        ProductionSystem.updateRefitRecalls(this);
        logisticsSystem.update(this, dt);
        itemPickupSystem.update(this);
        scoutSystem.update(this);
        npcSystemForActiveSystem().update(this, dt);
        updateOrganizedNpcFactions(dt);
        npcGalaxyDirector.update(this, dt);
        for (Unit unit : new ArrayList<>(units.values())) updateUnit(unit, dt);
        transferTouchingShips();
        weaponSystem.update(this, dt);
        cleanupDestroyed();
        saveActiveSystem();
    }

    private NpcSystem npcSystemForActiveSystem() {
        String activeId = activeSystemId();
        if (activeId == null || activeId.isBlank()) activeId = systemId();
        return npcSystems.computeIfAbsent(activeId, this::createNpcSystem);
    }

    private NpcSystem createNpcSystem(String systemId) {
        Set<String> disabled = new LinkedHashSet<>(disabledNpcFactionIds);
        for (NpcFaction faction : NpcRules.factions()) {
            if (faction.behavior() == NpcBehavior.FACTION || !NpcSystemScope.allows(systemId, faction.id())) {
                disabled.add(faction.id());
            }
        }
        return new NpcSystem(disabled);
    }

    private void updateOrganizedNpcFactions(double dt) {
        for (NpcFaction faction : NpcRules.factions()) {
            if (!faction.enabled() || faction.behavior() != NpcBehavior.FACTION
                    || disabledNpcFactionIds.contains(faction.id())) continue;

            PlayerRegistry.register(faction.id(), faction.name(), faction.rgb(), false);
            NpcFactionRuntime runtime = npcFactionRuntimes.computeIfAbsent(
                    faction.id(), ignored -> new NpcFactionRuntime(faction));
            boolean galaxyAssets = hasLiveAssets(faction.id());
            NpcFactionRuntime.State previousState = runtime.state();
            runtime.observe(galaxyAssets, faction);
            if (previousState == NpcFactionRuntime.State.ACTIVE
                    && runtime.state() == NpcFactionRuntime.State.RESPAWNING) {
                resetOrganizedNpcFactionState(faction, NpcFactionResetReason.DEFEATED);
            }

            if (hasLocalNpcAssets(faction.id())) {
                organizedNpcSystemForActiveSystem(faction).update(this, dt);
                continue;
            }
            if (galaxyAssets || !runtime.homeSystemId().equals(activeSystemId())) continue;
            if (!runtime.advanceSpawn(activeSystemId(), npcSpawnRequirementsMet(faction), dt)) continue;

            if (NpcFactionSpawner.spawn(this, faction)) runtime.markSpawned(faction);
            else runtime.deferSpawn(Math.max(1.0, faction.orderSeconds()));
        }
    }

    private NpcSystem organizedNpcSystemForActiveSystem(NpcFaction faction) {
        String activeId = activeSystemId();
        if (activeId == null || activeId.isBlank()) activeId = systemId();
        String key = activeId + "|" + faction.id();
        return organizedNpcSystems.computeIfAbsent(key, ignored -> {
            Set<String> disabled = new LinkedHashSet<>(disabledNpcFactionIds);
            for (NpcFaction candidate : NpcRules.factions()) {
                if (!candidate.id().equals(faction.id())) disabled.add(candidate.id());
            }
            return new NpcSystem(disabled);
        });
    }

    private boolean hasLocalNpcAssets(String factionId) {
        for (Unit unit : units.values()) if (factionId.equals(unit.playerId) && unit.hp > 0) return true;
        for (Base base : bases.values()) if (factionId.equals(base.playerId) && base.hp > 0) return true;
        return false;
    }

    private boolean npcSpawnRequirementsMet(NpcFaction faction) {
        if (!faction.requirePlayerCombatShips()) return true;
        int combatShips = 0;
        for (Unit unit : units.values()) {
            if (unit.hp <= 0 || NpcRules.isNpcFaction(unit.playerId)) continue;
            if (WeaponRules.armed(unit)) combatShips++;
        }
        return combatShips >= Math.max(1, faction.minPlayerCombatShips());
    }

    private void removeOrganizedNpcSystems(Set<String> deletedSystemIds) {
        if (deletedSystemIds == null || deletedSystemIds.isEmpty()) return;
        organizedNpcSystems.keySet().removeIf(key -> {
            int separator = key.indexOf('|');
            String systemId = separator < 0 ? key : key.substring(0, separator);
            return deletedSystemIds.contains(systemId);
        });
    }

    int npcRuntimeSystemCount() { return npcSystems.size(); }

    private void updateInactiveSystems(double dt) {
        if (dt == 0) return;
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

    private void updateUnit(Unit unit, double dt) {
        unit.unloadingThisFrame = false;
        unit.wormholeCooldown = Math.max(0, unit.wormholeCooldown - dt);
        if (ProductionSystem.refitLocked(this, unit.key())) {
            unit.task = UnitTask.IDLE;
            unit.attackTarget = "";
            unit.automationResourceId = -1;
            unit.clearOrder();
            unit.targetX = unit.x;
            unit.targetY = unit.y;
            unit.afterburnerActive = false;
            return;
        }
        if (ProductionSystem.refitReserved(this, unit.key())) {
            ShipModuleRules.update(this, unit, dt);
            unit.updatePosition(dt * SystemModifierRules.movementSpeed(this), width, height);
            return;
        }
        boolean recoveryOwned = NpcRecoverySystem.ownsUnit(this, unit)
                || NpcRepairEvacuationSystem.ownsUnit(this, unit);
        if (!recoveryOwned) {
            sendFullHarvestCargoToUnload(unit);
            autoUnload(unit, dt);
            haulerSystem.update(this, unit, dt);
            workSystem.update(this, unit, dt);
        }
        UnitOrderSystem.update(this, unit, dt);
        if (!recoveryOwned && unit.task == UnitTask.RETURN_TO_STATION) updateReturn(unit);
        if (!recoveryOwned && unit.task == UnitTask.IDLE && unit.orderType == UnitOrderType.NONE) idleNearBase(unit, dt);
        if (unit.task == UnitTask.MOVE && Calc.distance(unit.x, unit.y, unit.targetX, unit.targetY) < 5) unit.task = UnitTask.IDLE;
        ShipModuleRules.update(this, unit, dt);
        unit.updatePosition(dt * SystemModifierRules.movementSpeed(this), width, height);
    }
    private void sendFullHarvestCargoToUnload(Unit unit) { if (unit.type().harvestKinds.isEmpty() || unit.task == UnitTask.RETURN_TO_STATION || unit.cargoUsed() <= 0.05 || unit.freeCargo() > 0.05) return; sendToNearestBase(unit); }
    private void updateReturn(Unit unit) { Base base = nearestBase(unit.playerId, unit.x, unit.y); Unit depot = MobileDepot.preferredFor(this, unit, base); if (base == null && depot == null) { unit.task = UnitTask.IDLE; return; } if (unit.cargoUsed() <= 0.05) { ResourceNode resume = findResource(unit.automationResourceId); if (resume != null && resume.active) unit.task = UnitTask.AUTO_HARVEST; else if (!returnToMiningAnchor(unit)) unit.task = UnitTask.IDLE; return; } if (depot != null) moveTowardOrbit(unit, depot.x, depot.y, MobileDepot.range(depot) * 0.55); else moveTowardOrbit(unit, base.x, base.y, base.type().unloadRange * 0.55); }
    private void idleNearBase(Unit unit, double dt) { Base base = nearestBase(unit.playerId, unit.x, unit.y); if (base != null && Calc.distance(unit.x, unit.y, base.x, base.y) < base.type().unloadRange + 170) orbitAround(unit, base.x, base.y, unit.type().idleOrbitRadius, dt, 0.35); }
    private void autoUnload(Unit unit, double dt) { if (FuelShuttleSystem.SHUTTLE_TYPE.equals(unit.shipTypeId) || LogisticsSystem.SHUTTLE_TYPE.equals(unit.shipTypeId) || unit.cargoUsed() <= 0.05) return; Base base = nearestBase(unit.playerId, unit.x, unit.y); Unit depot = MobileDepot.preferredFor(this, unit, base); if (MobileDepot.transfer(unit, depot, dt)) return; if (base == null || Calc.distance(unit.x, unit.y, base.x, base.y) > base.type().unloadRange) return; double remaining = Math.min(base.type().unloadRate * dt, unit.cargoUsed()); for (Material material : Material.values()) { if (remaining <= 0.001) break; double held = unit.inventory.getOrDefault(material, 0.0); if (held <= 0.001) continue; double take = Math.min(held, remaining); unit.inventory.put(material, held - take); if (unit.inventory.getOrDefault(material, 0.0) <= 0.05) unit.inventory.remove(material); HangarStore.add(base.inventory, material, take); remaining -= take; unit.unloadingThisFrame = true; } }
    Base addBase(String type, double x, double y) { String id = "B" + nextBaseId++; Base base = new Base(id, localPlayerId, type, x, y); bases.put(id, base); return base; }
    Unit spawnShip(String type, double x, double y) { Unit unit = new Unit(localPlayerId, nextUnitId++, type, x, y); units.put(unit.key(), unit); return unit; }
    private int nextBaseNumber(String playerId) { int next = 1; String prefix = playerId + ":B"; for (Base base : bases.values()) if (base.id != null && base.id.startsWith(prefix)) { try { next = Math.max(next, Integer.parseInt(base.id.substring(prefix.length())) + 1); } catch (NumberFormatException ignored) { } } return next; }
    private int nextUnitNumber(String playerId) { int next = 1; for (Unit unit : units.values()) if (unit.playerId.equals(playerId)) next = Math.max(next, unit.unitId + 1); return next; }
    ProjectileShot addShot(String ownerId, String weaponId, String targetKey, double x, double y) { ProjectileShot shot = new ProjectileShot(nextShotId++, ownerId, weaponId, targetKey, x, y); shots.add(shot); return shot; }
    WorldItem addWorldItem(Material material, double amount, double x, double y, double vx, double vy, double angle, double spin) { WorldItem item = new WorldItem(nextWorldItemId++, material, amount, x, y, vx, vy, angle, spin); if (!item.empty()) items.add(item); return item.empty() ? null : item; }
    void explodeUnit(Unit unit) { if (unit != null) { explosions.add(ExplosionEffect.fromUnit(unit)); SystemAudio.playDestruction(this, unit.type().size.scale); } }
    void explodeBase(Base base) { if (base != null) { explosions.add(ExplosionEffect.fromBase(base)); SystemAudio.playDestruction(this, Math.max(2.0, base.type().maxHp / 900.0)); } }
    private void updateExplosions(double dt) { Iterator<ExplosionEffect> it = explosions.iterator(); while (it.hasNext()) if (!it.next().update(dt)) it.remove(); }
    boolean buildShip(String baseId, String shipTypeId) { return buildSystem.buildShip(this, baseId, shipTypeId); }
    boolean loadBasePackage(String baseId, String packageType) { return buildSystem.loadBasePackage(this, baseId, packageType); }
    boolean placePackage(Unit unit) { return buildSystem.placePackage(this, unit); }
    boolean craftItem(String baseId, String craftableId) { return buildSystem.craftItem(this, baseId, craftableId); }
    boolean research(String baseId, String topicId) { return buildSystem.research(this, baseId, topicId); }
    void draw(Graphics2D g2) { drawMap(g2); galaxy.draw(this, g2); for (Base base : bases.values()) base.draw(g2, localColor, stockpile, true); for (ResourceNode node : resources) node.draw(g2, node.id == selectedResourceId); for (WorldItem item : items) item.draw(g2); for (Unit unit : units.values()) { ResourceNode node = findResource(unit.automationResourceId); if (MiningBeam.visible(unit, node)) UnitRenderer.drawWorkLine(g2, unit, node); if (shouldDrawRoute(unit)) UnitRenderer.drawRoute(g2, unit, localColor); UnitOrderRenderer.draw(g2, this, unit); } weaponSystem.draw(g2, this); ShipModuleRules.draw(g2, this); for (ExplosionEffect explosion : explosions) explosion.draw(g2); for (Unit unit : units.values()) UnitRenderer.draw(g2, unit, localColor, true); }
    private boolean shouldDrawRoute(Unit unit) { return PlayerRegistry.isLocal(unit.playerId) && (unit.task == UnitTask.MOVE || unit.task == UnitTask.RETURN_TO_STATION || unit.task == UnitTask.ATTACK); }
    private void drawMap(Graphics2D g2) { galaxy.drawMap(g2, width, height); }
    void selectAt(double x, double y) { ResourceNode node = resourceAt(x, y); ResourceNetDebug.select(this, x, y, node); if (node != null) { selectedResourceId = node.id; status = "Targeted " + node.name + ". Right-click to auto-harvest."; return; } Unit unit = unitAt(x, y); for (Unit u : units.values()) u.selected = false; if (unit != null && PlayerRegistry.isLocal(unit.playerId)) { unit.selected = true; status = "Selected " + unit.type().name + " #" + unit.unitId + "."; } }
    void selectBox(Rectangle2D box) { for (Unit unit : units.values()) unit.selected = PlayerRegistry.isLocal(unit.playerId) && box.contains(unit.x, unit.y); status = selectedCount() + " ship(s) selected."; }
    void moveSelected(double x, double y) { moveSelected(x, y, FleetFormation.GRID); }
    void moveSelected(double x, double y, FleetFormation formation) { List<Unit> selected = selectedUnits(); if (selected.isEmpty()) { status = "No ship selected."; return; } int moved = 0; for (int i = 0; i < selected.size(); i++) { Unit unit = selected.get(i); if (ProductionSystem.refitReserved(this, unit.key())) continue; Point2D target = formationTarget(x, y, i, selected.size(), formation); unit.issueMove(target.getX(), target.getY()); moved++; } status = moved > 0 ? "Moving " + moved + " ship(s) in " + formation.label + " formation." : "Selected ship is reserved for refitting."; }
    void orderSelected(UnitOrderType type, double x1, double y1, double x2, double y2, String targetKey, FleetFormation formation) { List<Unit> selected = selectedUnits(); if (selected.isEmpty()) { status = "No ship selected."; return; } int applied = 0; for (int i = 0; i < selected.size(); i++) { Unit unit = selected.get(i); double ax = x1, ay = y1, bx = x2, by = y2; if (type == UnitOrderType.HOLD) { ax = bx = unit.x; ay = by = unit.y; } else if (type == UnitOrderType.ATTACK_MOVE) { Point2D end = formationTarget(x2, y2, i, selected.size(), formation); ax = unit.x; ay = unit.y; bx = end.getX(); by = end.getY(); } else if (type == UnitOrderType.PATROL) { Point2D start = formationTarget(x1, y1, i, selected.size(), formation); Point2D end = formationTarget(x2, y2, i, selected.size(), formation); ax = start.getX(); ay = start.getY(); bx = end.getX(); by = end.getY(); } else if (type == UnitOrderType.GUARD && (targetKey == null || targetKey.isBlank())) { Point2D anchor = formationTarget(x1, y1, i, selected.size(), formation); ax = bx = anchor.getX(); ay = by = anchor.getY(); } double radius = UnitOrderSystem.defaultRadius(type); if (AUnitOrder.apply(this, new UnitOrderCommand(unit.playerId, unit.unitId, type, ax, ay, bx, by, radius, targetKey, 0))) applied++; } status = applied > 0 ? orderLabel(type) + " order assigned to " + applied + " ship(s)." : "Unable to assign " + orderLabel(type).toLowerCase(Locale.ROOT) + " order."; }
    private String orderLabel(UnitOrderType type) { return switch (type) { case PATROL -> "Patrol"; case GUARD -> "Guard"; case ESCORT -> "Escort"; case HOLD -> "Hold position"; case ATTACK_MOVE -> "Attack-move"; case NONE -> "No"; }; }
    private Point2D formationTarget(double x, double y, int index, int count, FleetFormation formation) { double spacing = 54, ox = 0, oy = 0; switch (formation) { case LINE -> ox = (index - (count - 1) / 2.0) * spacing; case COLUMN -> oy = (index - (count - 1) / 2.0) * spacing; case WEDGE -> { if (index > 0) { int rank = (index + 1) / 2; int side = index % 2 == 1 ? -1 : 1; ox = side * rank * spacing; oy = rank * spacing; } } case GRID -> { int cols = (int)Math.ceil(Math.sqrt(count)); double rows = Math.ceil(count / (double)cols); int col = index % cols; int row = index / cols; ox = (col - (cols - 1) / 2.0) * 42; oy = (row - (rows - 1) / 2.0) * 42; } } return new Point2D.Double(Calc.clamp(x + ox, 0, width), Calc.clamp(y + oy, 0, height)); }
    void attackSelected(String targetKey) { int started = 0, unarmed = 0, refitting = 0; for (Unit unit : selectedUnits()) { if (ProductionSystem.refitReserved(this, unit.key())) { refitting++; continue; } if (WeaponRules.armed(unit)) { if (!CombatTarget.enemy(this, unit, targetKey)) continue; unit.issueAttack(targetKey); started++; } else unarmed++; } status = started > 0 ? "Attacking target with " + started + " ship(s)." : refitting > 0 ? "Selected ship is reserved for refitting." : unarmed > 0 ? "Selected ship has no weapons." : "No valid attack target."; }
    void autoHarvestSelected(ResourceNode node) { int started = 0, refitting = 0; for (Unit unit : selectedUnits()) { if (ProductionSystem.refitReserved(this, unit.key())) { refitting++; continue; } if (!unit.type().harvestKinds.contains(node.kind)) continue; unit.setMiningAnchor(node.x, node.y); unit.startAutoHarvest(node.id); started++; } status = started > 0 ? "Auto-harvesting " + node.name + "." : refitting > 0 ? "Selected ship is reserved for refitting." : "Selected ship cannot harvest this node."; }
    void sendToNearestBase(Unit unit) { Base base = nearestBase(unit.playerId, unit.x, unit.y); Unit depot = MobileDepot.preferredFor(this, unit, base); if (base == null && depot == null) return; unit.task = UnitTask.RETURN_TO_STATION; if (depot != null) moveTowardOrbit(unit, depot.x, depot.y, MobileDepot.range(depot) * 0.55); else moveTowardOrbit(unit, base.x, base.y, base.type().unloadRange * 0.55); }
    boolean returnToMiningAnchor(Unit unit) { if (unit == null || !unit.miningAnchorSet || unit.type().harvestKinds.isEmpty()) return false; unit.moveTo(unit.miningAnchorX, unit.miningAnchorY); return true; }
    boolean scoutRetarget(Unit unit, ResourceNode oldNode) { return oldNode != null && scoutSystem.retargetAfterDepletion(this, unit, oldNode); }
    void orbitAround(Unit unit, double cx, double cy, double radius, double dt, double speed) { unit.orbitAngle += dt * speed * (unit.unitId % 2 == 0 ? 1 : -1); unit.targetX = Calc.clamp(cx + Math.cos(unit.orbitAngle) * radius, 0, width); unit.targetY = Calc.clamp(cy + Math.sin(unit.orbitAngle) * radius, 0, height); unit.orbitRetarget = 0; }
    void moveTowardOrbit(Unit unit, double cx, double cy, double radius) { double angle = Math.atan2(unit.y - cy, unit.x - cx); if (Double.isNaN(angle)) angle = unit.unitId; unit.targetX = Calc.clamp(cx + Math.cos(angle) * radius, 0, width); unit.targetY = Calc.clamp(cy + Math.sin(angle) * radius, 0, height); }
    void relocateResource(ResourceNode node) { ResourceSpawner.relocate(node, resources, bases.values(), celestials, random); }
    private void cleanupDestroyed() { Iterator<Unit> unitIt = units.values().iterator(); while (unitIt.hasNext()) { Unit unit = unitIt.next(); if (unit.hp <= 0) { dropLoot(unit); explodeUnit(unit); unitIt.remove(); } } Iterator<Base> baseIt = bases.values().iterator(); while (baseIt.hasNext()) { Base base = baseIt.next(); if (base.hp <= 0) { dropLoot(base); explodeBase(base); baseIt.remove(); } } NpcStationReplacementSystem.replaceMissingStations(this); NpcCollapseSystem.removeShipsWithoutStations(this); shots.removeIf(shot -> !CombatTarget.alive(this, shot.targetKey) || shot.weapon() == null); for (Unit unit : units.values()) if (!unit.attackTarget.isBlank() && !CombatTarget.alive(this, unit.attackTarget)) { unit.attackTarget = ""; if (unit.task == UnitTask.ATTACK) unit.task = UnitTask.IDLE; } }
    private void dropLoot(Unit unit) { int count = WorldLootDrops.scatter(this, SalvageDrops.fromUnit(unit), unit.x, unit.y, Math.max(1.0, unit.type().size.scale), lootSeed(unit.key(), unit.x, unit.y)); if (count > 0 && PlayerRegistry.isLocal(unit.playerId)) status = "Destroyed ship dropped cargo and salvage."; }
    private void dropLoot(Base base) { double power = Math.max(2.4, base.type().maxHp / 900.0); int count = WorldLootDrops.scatter(this, SalvageDrops.fromBase(base), base.x, base.y, power, lootSeed(base.id, base.x, base.y)); if (count > 0 && PlayerRegistry.isLocal(base.playerId)) status = "Destroyed station dropped hangar loot and salvage."; }
    private long lootSeed(String key, double x, double y) { return System.nanoTime() ^ ((long)key.hashCode() << 32) ^ Double.doubleToLongBits(x * 37.0 + y * 41.0); }
    ResourceNode resourceAt(double x, double y) { ResourceNode best = null; double bestDist = Double.MAX_VALUE; for (ResourceNode node : resources) if (node.active) { double d = Calc.distance(x, y, node.x, node.y); if (d <= node.radius + 14 && d < bestDist) { best = node; bestDist = d; } } return best; }
    Base baseAt(double x, double y) { for (Base base : bases.values()) if (base.contains(x, y)) return base; return null; }
    Unit unitAt(double x, double y) { for (Unit unit : units.values()) if (unit.contains(x, y)) return unit; return null; }
    ResourceNode findResource(int id) { for (ResourceNode node : resources) if (node.id == id) return node; return null; }
    Base nearestBase(double x, double y) { return nearestBase(PlayerRegistry.localId(), x, y); }
    Base nearestBase(String playerId, double x, double y) { return galaxy.nearestBaseInSameSystem(this, playerId, x, y); }
    List<Unit> selectedUnits() { List<Unit> out = new ArrayList<>(); for (Unit unit : units.values()) if (unit.selected && PlayerRegistry.isLocal(unit.playerId)) out.add(unit); return out; }
    Unit selectedUnit() { for (Unit unit : units.values()) if (unit.selected && PlayerRegistry.isLocal(unit.playerId)) return unit; return null; }
    int selectedCount() { int count = 0; for (Unit unit : units.values()) if (unit.selected && PlayerRegistry.isLocal(unit.playerId)) count++; return count; }
    boolean canAfford(List<Cost> cost) { for (Cost c : cost) if (stockpile.getOrDefault(c.material(), 0.0) + 0.001 < c.amount()) return false; return true; }
    void spend(List<Cost> cost) { for (Cost c : cost) { double next = stockpile.getOrDefault(c.material(), 0.0) - c.amount(); if (next <= 0.05) stockpile.remove(c.material()); else stockpile.put(c.material(), next); } }
    Rectangle2D localBounds() { boolean found = false; double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE; for (Unit u : units.values()) if (PlayerRegistry.isLocal(u.playerId)) { found = true; minX = Math.min(minX, u.x); minY = Math.min(minY, u.y); maxX = Math.max(maxX, u.x); maxY = Math.max(maxY, u.y); } for (Base b : bases.values()) if (PlayerRegistry.isLocal(b.playerId)) { found = true; minX = Math.min(minX, b.x); minY = Math.min(minY, b.y); maxX = Math.max(maxX, b.x); maxY = Math.max(maxY, b.y); } return found ? new Rectangle2D.Double(minX, minY, Math.max(1, maxX - minX), Math.max(1, maxY - minY)) : null; }
}
