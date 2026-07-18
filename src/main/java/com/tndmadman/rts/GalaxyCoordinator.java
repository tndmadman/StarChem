package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.Point2D;
import java.util.*;
import java.util.List;

final class GalaxyCoordinator {
    private static final double WORMHOLE_EXIT_BUFFER = 96.0;
    private static final double WORMHOLE_COOLDOWN_SECONDS = 0.75;

    private final Map<String, WorldSystemState> systems = new LinkedHashMap<>();
    private final Map<String, String> playerHomes = new LinkedHashMap<>();
    private String activeSystemId;
    private String entrySystemId;
    private long seed;
    private int nextResourceId = 1;

    CelestialSystem rebuild(World world, StarSystemDefinition primary, long seed) {
        this.seed = seed;
        systems.clear();
        playerHomes.clear();
        clearWorld(world);
        nextResourceId = 1;

        GalaxyPlan plan = GalaxyPlanner.standard(primary.id(), GalaxyRuntimeOptions.copiesPerTemplate(), seed);
        entrySystemId = plan.entrySystemId();
        for (GalaxyInstanceSpec spec : plan.systems()) {
            createSystem(spec.id(), spec.templateId(), StarSystems.get(spec.templateId()), spec.lifetime(), spec.initialControllerId());
        }
        for (GalaxyLinkSpec spec : plan.links()) link(world, systems.get(spec.fromSystemId()), systems.get(spec.toSystemId()));

        activeSystemId = systems.containsKey(entrySystemId) ? entrySystemId : fallbackActiveSystemId();
        loadActive(world);
        return activeCelestials();
    }

    private void clearWorld(World world) {
        world.resources.clear();
        world.units.clear();
        world.bases.clear();
        world.shots.clear();
        world.items.clear();
        world.wormholes.clear();
    }

    String activeSystemId() { return activeSystemId; }
    String activeControllerId() { WorldSystemState state = active(); return state == null ? "" : state.control.controllerId(); }
    CelestialSystem activeCelestials() { WorldSystemState state = active(); return state == null ? null : state.celestials; }
    double activeSystemTime() { WorldSystemState state = active(); return state == null ? 0 : state.systemTime; }
    void setActiveSystemTime(double time) { WorldSystemState state = active(); if (state != null) state.systemTime = Math.max(0, time); }

    Map<String,Object> captureSave(World world) {
        saveActive(world);
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("seed", seed);
        out.put("activeSystemId", activeSystemId);
        out.put("entrySystemId", entrySystemId);
        out.put("nextResourceId", nextResourceId);
        out.put("playerHomes", new LinkedHashMap<>(playerHomes));
        List<Object> savedSystems = new ArrayList<>();
        for (WorldSystemState state : systems.values()) savedSystems.add(captureSystem(state));
        out.put("systems", savedSystems);
        return out;
    }

    CelestialSystem restoreSave(World world, Map<String,Object> save) {
        if (save == null) throw new IllegalArgumentException("Save is missing galaxy data.");
        systems.clear();
        playerHomes.clear();
        clearWorld(world);
        seed = ServerSaveStore.longValue(save, "seed", System.nanoTime() ^ System.currentTimeMillis());
        entrySystemId = ServerSaveStore.string(save, "entrySystemId", "");
        activeSystemId = ServerSaveStore.string(save, "activeSystemId", "");
        nextResourceId = Math.max(1, ServerSaveStore.intValue(save, "nextResourceId", 1));
        Map<String,Object> homes = ServerSaveStore.object(save.get("playerHomes"));
        for (Map.Entry<String,Object> entry : homes.entrySet()) playerHomes.put(entry.getKey(), ServerSaveStore.asString(entry.getValue(), ""));
        for (Object item : ServerSaveStore.list(save.get("systems"))) restoreSystem(ServerSaveStore.object(item));
        if (activeSystemId == null || activeSystemId.isBlank() || !systems.containsKey(activeSystemId)) activeSystemId = fallbackActiveSystemId();
        if (entrySystemId == null || entrySystemId.isBlank()) entrySystemId = activeSystemId;
        loadActive(world);
        return activeCelestials();
    }

    void saveActive(World world) {
        WorldSystemState state = active();
        if (state == null) return;
        state.systemTime = world.systemTime();
        state.resources.clear(); state.resources.addAll(world.resources);
        state.units.clear(); state.units.putAll(world.units);
        state.bases.clear(); state.bases.putAll(world.bases);
        state.shots.clear(); state.shots.addAll(world.shots);
        state.items.clear(); state.items.addAll(world.items);
    }

    CelestialSystem activate(World world, String systemId) {
        saveActive(world);
        WorldSystemState state = systems.get(systemId);
        if (state == null) return activeCelestials();
        activeSystemId = state.id;
        loadActive(world);
        return state.celestials;
    }

    boolean viewSystem(World world, String systemId) {
        saveActive(world);
        WorldSystemState state = systems.get(systemId);
        if (state == null) return false;
        activeSystemId = state.id;
        loadActive(world);
        world.status = "Galaxy map: travelled to " + state.definition.name() + ".";
        return true;
    }

    GalaxyMapSnapshot mapSnapshot(World world) {
        saveActive(world);
        List<GalaxyMapSystem> mapSystems = new ArrayList<>();
        for (WorldSystemState state : systems.values()) {
            int activeResources = 0;
            for (ResourceNode node : state.resources) if (node.active) activeResources++;
            int localShips = 0;
            for (Unit unit : state.units.values()) if (PlayerRegistry.isLocal(unit.playerId) && unit.hp > 0) localShips++;
            int localBases = 0;
            for (Base base : state.bases.values()) if (PlayerRegistry.isLocal(base.playerId) && base.hp > 0) localBases++;

            SystemControlState control = state.control;
            String colorOwner = control.status() == SystemControlStatus.CAPTURING && !control.claimantId().isBlank()
                    ? control.claimantId() : control.controllerId();
            mapSystems.add(new GalaxyMapSystem(
                    state.id,
                    displayName(state),
                    state.templateId,
                    state.lifetime,
                    state.units.size(),
                    state.bases.size(),
                    activeResources,
                    localShips,
                    localBases,
                    state.id.equals(activeSystemId),
                    state.isPlayerHome(),
                    state.isStatic(),
                    control.controllerId(),
                    ownerName(control.controllerId()),
                    control.status(),
                    control.captureProgress(),
                    ownerColor(colorOwner)));
        }

        List<GalaxyMapLink> links = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (WorldSystemState state : systems.values()) {
            for (WormholeGate gate : state.wormholes) {
                if (!systems.containsKey(gate.toSystemId)) continue;
                String a = state.id.compareTo(gate.toSystemId) <= 0 ? state.id : gate.toSystemId;
                String b = state.id.compareTo(gate.toSystemId) <= 0 ? gate.toSystemId : state.id;
                if (seen.add(a + "->" + b)) links.add(new GalaxyMapLink(a, b));
            }
        }
        return new GalaxyMapSnapshot(activeSystemId, List.copyOf(mapSystems), List.copyOf(links));
    }

    private String displayName(WorldSystemState state) {
        return state.id.endsWith("_2") ? state.definition.name() + " II" : state.definition.name();
    }

    private String ownerName(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) return "Neutral";
        for (NpcFaction faction : NpcRules.factions()) if (faction.id().equals(ownerId)) return faction.name();
        return PlayerRegistry.name(ownerId);
    }

    private int ownerColor(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) return 0x8A96A3;
        for (NpcFaction faction : NpcRules.factions()) if (faction.id().equals(ownerId)) return faction.rgb() & 0xFFFFFF;
        return PlayerRegistry.color(ownerId).getRGB() & 0xFFFFFF;
    }

    GalaxySystem ensurePlayerHome(World world, String playerId, StarSystemDefinition primary) {
        return ensurePlayerHome(world, playerId, primary, false);
    }

    GalaxySystem ensurePlayerHome(World world, String playerId, StarSystemDefinition primary, boolean usePrimaryDefinition) {
        if (playerId == null || playerId.isBlank()) playerId = world.localPlayerId;
        String existing = playerHomes.get(playerId);
        if (existing != null && systems.containsKey(existing)) return asGalaxySystem(systems.get(existing));

        StarSystemDefinition definition = usePrimaryDefinition ? primary : StarSystems.get(StarSystems.PLAYER_HOME_SYSTEM_ID);
        WorldSystemState home = createSystem(playerHomeId(playerId), definition.id(), definition, SystemLifetime.PLAYER_HOME, playerId);
        home.control.protect(playerId);
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

    String playerHomeSystemId(World world, String playerId, StarSystemDefinition primary) { return ensurePlayerHome(world, playerId, primary).id; }
    List<Material> spawnMaterials(World world, String playerId, StarSystemDefinition primary) { return ensurePlayerHome(world, playerId, primary).definition.spawnMaterials(); }

    Point2D startPoint(World world, String playerId, int slot, StarSystemDefinition primary) {
        ensurePlayerHome(world, playerId, primary);
        WorldSystemState state = systems.get(playerHomes.get(playerId));
        if (state == null) state = active();
        List<Material> materials = state.definition.spawnMaterials();
        Material material = materials.isEmpty() ? Material.IRON : materials.get(Math.floorMod(slot, materials.size()));
        ResourceNode node = nthActiveResource(state, material, slot * 17);
        if (node == null) return new Point2D.Double(state.width() * 0.32, state.height() * 0.56);
        double a = Math.atan2(node.y - state.celestials.sunY(), node.x - state.celestials.sunX());
        double r = Math.max(700, Math.hypot(node.x - state.celestials.sunX(), node.y - state.celestials.sunY()) - 260);
        return new Point2D.Double(state.celestials.sunX() + Math.cos(a) * r, state.celestials.sunY() + Math.sin(a) * r);
    }

    Point2D npcSpawnPoint(World world, String factionId, double padding) {
        WorldSystemState state = active();
        if (state == null) return new Point2D.Double(world.width / 2.0, world.height / 2.0);
        double angle = Math.floorMod(factionId == null ? 0 : factionId.hashCode(), 360) * Math.PI / 180.0;
        double radius = Math.min(state.width(), state.height()) * 0.24;
        return new Point2D.Double(
                Calc.clamp(state.width() * 0.5 + Math.cos(angle) * radius, padding, state.width() - padding),
                Calc.clamp(state.height() * 0.5 + Math.sin(angle) * radius, padding, state.height() - padding));
    }

    void update(World world, double dt) {
        WorldSystemState state = active();
        if (state == null || dt == 0) return;
        state.systemTime += dt;
        state.celestials.update(dt);
        SystemControlSystem.update(world, state, dt);
    }

    void advanceVisual(double dt) {
        if (!Double.isFinite(dt) || dt == 0) return;
        advanceVisualState(active(), dt);
    }

    void syncVisual(World world, String systemId, double hostTime) {
        if (world == null || systemId == null || systemId.isBlank() || !Double.isFinite(hostTime) || hostTime < 0) return;
        WorldSystemState state = systems.get(systemId);
        if (state == null) return;
        if (!state.id.equals(activeSystemId)) {
            saveActive(world);
            activeSystemId = state.id;
            loadActive(world);
        }
        double delta = hostTime - state.systemTime;
        if (Math.abs(delta) > 0.000001) advanceVisualState(state, delta);
        state.systemTime = hostTime;
    }

    void updateInactiveSystems(double dt) {
        if (dt == 0) return;
        for (WorldSystemState state : systems.values()) {
            if (state == null || state.id.equals(activeSystemId)) continue;
            advanceVisualState(state, dt);
        }
    }

    private void advanceVisualState(WorldSystemState state, double dt) {
        if (state == null || !Double.isFinite(dt) || dt == 0) return;
        state.systemTime += dt;
        state.celestials.update(dt);
        for (ResourceNode node : state.resources) node.updateOrbit(state.celestials.sunX(), state.celestials.sunY(), dt);
    }

    void draw(World world, Graphics2D g2) {
        WorldSystemState state = active();
        if (state != null) {
            state.celestials.draw(g2);
            String owner = state.control.status() == SystemControlStatus.CAPTURING ? state.control.claimantId() : state.control.controllerId();
            state.controlPoint.draw(g2, state.control, ownerColor(owner));
        }
        for (WormholeGate gate : world.wormholes) gate.draw(g2);
    }

    void drawMap(Graphics2D g2, int width, int height) {
        g2.setColor(new Color(9, 15, 24));
        g2.fillRect(0, 0, width, height);
        g2.setColor(new Color(22, 33, 48));
        for (int x = 0; x <= width; x += 160) g2.drawLine(x, 0, x, height);
        for (int y = 0; y <= height; y += 160) g2.drawLine(0, y, width, y);
        WorldSystemState state = active();
        if (state != null) {
            g2.setColor(new Color(220, 238, 250, 190));
            g2.drawString(state.definition.name() + " [" + state.id + "]", 24, 32);
        }
    }

    boolean hasLiveAssets(World world, String playerId) {
        saveActive(world);
        if (playerId == null || playerId.isBlank() || "WAIT".equals(playerId)) return true;
        for (WorldSystemState state : systems.values()) {
            for (Unit unit : state.units.values()) if (unit.playerId.equals(playerId) && unit.hp > 0) return true;
            for (Base base : state.bases.values()) if (base.playerId.equals(playerId) && base.hp > 0) return true;
        }
        return false;
    }

    Set<String> removePlayerAndPruneEmptySystems(World world, String playerId) {
        if (playerId == null || playerId.isBlank() || "WAIT".equals(playerId)) return Set.of();
        saveActive(world);
        for (WorldSystemState state : systems.values()) {
            state.units.values().removeIf(unit -> playerId.equals(unit.playerId));
            state.bases.values().removeIf(base -> playerId.equals(base.playerId));
            state.shots.removeIf(shot -> playerId.equals(shot.ownerId) || targetsPlayer(shot.targetKey, playerId));
        }
        playerHomes.remove(playerId);
        return pruneAbandonedSystemsAfterSave(world);
    }

    Set<String> pruneAbandonedSystems(World world) {
        saveActive(world);
        return pruneAbandonedSystemsAfterSave(world);
    }

    Base nearestBaseInSameSystem(World world, String playerId, double x, double y) {
        Base best = null;
        double bestDist = Double.MAX_VALUE;
        for (Base base : world.bases.values()) {
            if (!base.playerId.equals(playerId)) continue;
            double d = Calc.distance(x, y, base.x, base.y);
            if (d < bestDist) { best = base; bestDist = d; }
        }
        return best;
    }

    boolean viewThrough(World world, WormholeGate gate) {
        WorldSystemState to = systems.get(gate.toSystemId);
        if (to == null) return false;
        saveActive(world);
        activeSystemId = to.id;
        loadActive(world);
        world.status = "Viewing " + to.definition.name() + ". Ships travel only when they touch the wormhole.";
        return true;
    }

    boolean transferTouchingShips(World world) { return transferTouchingShips(world, ""); }

    boolean transferTouchingShips(World world, String playerId) {
        List<Unit> unitsToMove = new ArrayList<>(world.units.values());
        boolean moved = false;
        boolean allPlayers = playerId == null || playerId.isBlank();
        for (Unit unit : unitsToMove) {
            if (!allPlayers && !playerId.equals(unit.playerId)) continue;
            if (unit.wormholeCooldown > 0) continue;
            WormholeGate gate = touchingGate(world, unit);
            if (gate == null) continue;
            moved |= transferUnit(world, gate, unit);
        }
        return moved;
    }

    private WormholeGate touchingGate(World world, Unit unit) {
        for (WormholeGate gate : world.wormholes) if (gate.contains(unit.x, unit.y)) return gate;
        return null;
    }

    private boolean transferUnit(World world, WormholeGate gate, Unit unit) {
        WorldSystemState from = active();
        WorldSystemState to = systems.get(gate.toSystemId);
        if (from == null || to == null || unit == null || !world.units.containsKey(unit.key())) return false;
        String previous = activeSystemId;
        double rawExitX = gate.exitX;
        double rawExitY = gate.exitY;
        world.units.remove(unit.key());
        saveActive(world);
        activeSystemId = to.id;
        loadActive(world);
        Point2D exit = safeExitPoint(world, unit, rawExitX, rawExitY);
        unit.x = exit.getX();
        unit.y = exit.getY();
        unit.targetX = unit.x;
        unit.targetY = unit.y;
        unit.miningAnchorX = unit.x;
        unit.miningAnchorY = unit.y;
        unit.automationResourceId = -1;
        unit.attackTarget = "";
        unit.task = UnitTask.IDLE;
        unit.wormholeCooldown = WORMHOLE_COOLDOWN_SECONDS;
        world.units.put(unit.key(), unit);
        saveActive(world);
        activeSystemId = previous;
        loadActive(world);
        world.status = unit.type().name + " entered wormhole to " + to.definition.name() + ".";
        return true;
    }

    boolean moveNpcExpedition(World world, String factionId, String targetSystemId, int requestedCombatShips) {
        WorldSystemState source = active();
        WorldSystemState target = systems.get(targetSystemId);
        if (source == null || target == null || source == target || !wormholeExists(source, target.id)) return false;

        List<Unit> combat = new ArrayList<>();
        Unit builder = null;
        Unit worker = null;
        for (Unit unit : world.units.values()) {
            if (!factionId.equals(unit.playerId) || unit.hp <= 0) continue;
            if (builder == null && unit.type().baseBuilder && unit.basePackageType.isBlank()) builder = unit;
            else if (worker == null && !unit.type().harvestKinds.isEmpty()) worker = unit;
            if (WeaponRules.armed(unit.type())) combat.add(unit);
        }
        int fleetSize = Math.max(2, requestedCombatShips);
        if (builder == null || worker == null || combat.size() < fleetSize) return false;
        combat = new ArrayList<>(combat.subList(0, Math.min(fleetSize, combat.size())));

        Base sourceBase = null;
        for (Base base : world.bases.values()) {
            if (factionId.equals(base.playerId) && base.hp > 0) { sourceBase = base; break; }
        }
        if (sourceBase == null) return false;

        EnumMap<Material, Double> supplies = new EnumMap<>(Material.class);
        for (Material material : Material.values()) {
            if (!material.raw && material != Material.FUEL) continue;
            double held = sourceBase.inventory.getOrDefault(material, 0.0);
            double take = Math.min(250.0, held * 0.20);
            if (take <= 0.05) continue;
            supplies.put(material, take);
            double left = held - take;
            if (left <= 0.05) sourceBase.inventory.remove(material); else sourceBase.inventory.put(material, left);
        }

        for (Unit unit : combat) world.units.remove(unit.key());
        world.units.remove(worker.key());
        world.units.remove(builder.key());
        String previous = activeSystemId;
        saveActive(world);
        activeSystemId = target.id;
        loadActive(world);

        Point2D anchor = npcSpawnPoint(world, factionId, 700);
        String baseId = factionId + ":B" + nextNpcBaseNumber(target, factionId);
        Base foothold = new Base(baseId, factionId, Rules.DEFAULT_BASE, anchor.getX(), anchor.getY());
        foothold.inventory.putAll(supplies);
        world.bases.put(baseId, foothold);
        int index = 0;
        for (Unit unit : combat) placeExpeditionUnit(world, unit, anchor, index++);
        placeExpeditionUnit(world, worker, anchor, index);
        saveActive(world);

        activeSystemId = previous;
        loadActive(world);
        return true;
    }

    private void placeExpeditionUnit(World world, Unit unit, Point2D anchor, int index) {
        double angle = index * 1.7;
        double radius = 190 + index * 18;
        unit.x = Calc.clamp(anchor.getX() + Math.cos(angle) * radius, 0, world.width);
        unit.y = Calc.clamp(anchor.getY() + Math.sin(angle) * radius, 0, world.height);
        unit.targetX = unit.x;
        unit.targetY = unit.y;
        unit.attackTarget = "";
        unit.automationResourceId = -1;
        unit.task = UnitTask.IDLE;
        unit.wormholeCooldown = WORMHOLE_COOLDOWN_SECONDS;
        world.units.put(unit.key(), unit);
    }

    private int nextNpcBaseNumber(WorldSystemState state, String factionId) {
        int max = 0;
        String prefix = factionId + ":B";
        for (String id : state.bases.keySet()) {
            if (!id.startsWith(prefix)) continue;
            try { max = Math.max(max, Integer.parseInt(id.substring(prefix.length()))); }
            catch (NumberFormatException ignored) { }
        }
        return max + 1;
    }

    void moveAssetsToSystem(World world, String playerId, String targetSystemId) {
        WorldSystemState target = systems.get(targetSystemId);
        if (target == null || activeSystemId.equals(targetSystemId)) return;
        List<Unit> movingUnits = new ArrayList<>();
        List<Base> movingBases = new ArrayList<>();
        for (Unit unit : world.units.values()) if (unit.playerId.equals(playerId)) movingUnits.add(unit);
        for (Base base : world.bases.values()) if (base.playerId.equals(playerId)) movingBases.add(base);
        if (movingUnits.isEmpty() && movingBases.isEmpty()) return;
        for (Unit unit : movingUnits) world.units.remove(unit.key());
        for (Base base : movingBases) world.bases.remove(base.id);
        String previous = activeSystemId;
        saveActive(world);
        activeSystemId = targetSystemId;
        loadActive(world);
        Point2D anchor = npcSpawnPoint(world, playerId, 700);
        double sourceX = movingBases.isEmpty() ? movingUnits.get(0).x : movingBases.get(0).x;
        double sourceY = movingBases.isEmpty() ? movingUnits.get(0).y : movingBases.get(0).y;
        double dx = anchor.getX() - sourceX;
        double dy = anchor.getY() - sourceY;
        for (Base base : movingBases) world.bases.put(base.id, movedBase(base, dx, dy, world.width, world.height));
        for (Unit unit : movingUnits) { moveUnit(unit, dx, dy, world.width, world.height); world.units.put(unit.key(), unit); }
        saveActive(world);
        activeSystemId = previous;
        loadActive(world);
    }

    private WorldSystemState createSystem(String id, String templateId, StarSystemDefinition definition,
                                          SystemLifetime lifetime, String initialControllerId) {
        WorldSystemState existing = systems.get(id);
        if (existing != null) return existing;
        Random systemRandom = new Random(seed ^ ((long)id.hashCode() << 21) ^ definition.id().hashCode());
        CelestialSystem celestials = new CelestialSystem(definition, systemRandom);
        WorldSystemState state = new WorldSystemState(id, templateId, definition, lifetime, initialControllerId, celestials);
        nextResourceId = ResourceSpawner.seed(state.resources, celestials, systemRandom, nextResourceId, definition.resourceBelts());
        systems.put(id, state);
        return state;
    }

    private void link(World world, WorldSystemState a, WorldSystemState b) {
        if (a == null || b == null || a == b || a.id.equals(b.id)) return;
        boolean aHas = wormholeExists(a, b.id);
        boolean bHas = wormholeExists(b, a.id);
        if (aHas && bHas) return;
        Point2D ap = wormholePoint(a, a.wormholes.size(), b.id);
        Point2D bp = wormholePoint(b, b.wormholes.size(), a.id);
        Point2D aExit = exitPoint(a, ap);
        Point2D bExit = exitPoint(b, bp);
        if (!aHas) a.wormholes.add(new WormholeGate(a.id + "_to_" + b.id, a.id, b.id, ap.getX(), ap.getY(), bExit.getX(), bExit.getY()));
        if (!bHas) b.wormholes.add(new WormholeGate(b.id + "_to_" + a.id, b.id, a.id, bp.getX(), bp.getY(), aExit.getX(), aExit.getY()));
        if (a.id.equals(activeSystemId) || b.id.equals(activeSystemId)) loadActive(world);
    }

    private Set<String> pruneAbandonedSystemsAfterSave(World world) {
        Set<String> deleted = new LinkedHashSet<>();
        for (WorldSystemState state : new ArrayList<>(systems.values())) {
            if (canPruneSystem(state) && !hasPlayerAssets(state)) deleted.add(state.id);
        }
        if (deleted.isEmpty()) { loadActive(world); return Set.of(); }
        deleteSystems(world, deleted);
        return Set.copyOf(deleted);
    }

    private void deleteSystems(World world, Set<String> deleted) {
        for (String systemId : deleted) systems.remove(systemId);
        playerHomes.values().removeIf(deleted::contains);
        for (WorldSystemState state : systems.values()) state.wormholes.removeIf(gate -> deleted.contains(gate.toSystemId));
        if (activeSystemId == null || deleted.contains(activeSystemId) || !systems.containsKey(activeSystemId)) activeSystemId = fallbackActiveSystemId();
        loadActive(world);
    }

    private boolean targetsPlayer(String targetKey, String playerId) {
        return targetKey != null && playerId != null && targetKey.startsWith(playerId + ":");
    }

    private boolean canPruneSystem(WorldSystemState state) {
        return state != null && state.lifetime != SystemLifetime.STATIC;
    }

    private boolean hasPlayerAssets(WorldSystemState state) {
        if (state == null) return false;
        for (Unit unit : state.units.values()) if (isPlayerAssetOwner(unit.playerId) && unit.hp > 0) return true;
        for (Base base : state.bases.values()) if (isPlayerAssetOwner(base.playerId) && base.hp > 0) return true;
        return false;
    }

    private boolean isPlayerAssetOwner(String playerId) {
        return playerId != null && !playerId.isBlank() && !"WAIT".equals(playerId) && !NpcRules.isNpcFaction(playerId);
    }

    private String fallbackActiveSystemId() {
        if (entrySystemId != null && systems.containsKey(entrySystemId)) return entrySystemId;
        return systems.isEmpty() ? null : systems.keySet().iterator().next();
    }

    private Point2D wormholePoint(WorldSystemState state, int index, String otherSystemId) {
        int slot = Math.floorMod(index, 16);
        double jitter = Math.floorMod(otherSystemId == null ? 0 : otherSystemId.hashCode(), 37) / 37.0 * 0.22;
        double angle = -Math.PI / 2.0 + slot * (Math.PI * 2.0 / 16.0) + jitter;
        double rx = state.width() * 0.36;
        double ry = state.height() * 0.31;
        double x = state.width() * 0.5 + Math.cos(angle) * rx;
        double y = state.height() * 0.5 + Math.sin(angle) * ry;
        return new Point2D.Double(Calc.clamp(x, 220, state.width() - 220), Calc.clamp(y, 220, state.height() - 220));
    }

    private Point2D exitPoint(WorldSystemState state, Point2D gate) {
        double cx = state.width() * 0.5;
        double cy = state.height() * 0.5;
        double dx = cx - gate.getX();
        double dy = cy - gate.getY();
        double len = Math.max(1.0, Math.hypot(dx, dy));
        return new Point2D.Double(Calc.clamp(gate.getX() + dx / len * 520, 120, state.width() - 120), Calc.clamp(gate.getY() + dy / len * 520, 120, state.height() - 120));
    }

    private Point2D safeExitPoint(World world, Unit unit, double startX, double startY) {
        double clearance = unitClearance(unit);
        double x = Calc.clamp(startX, clearance, world.width - clearance);
        double y = Calc.clamp(startY, clearance, world.height - clearance);
        int passes = Math.max(1, world.wormholes.size() + 4);
        for (int pass = 0; pass < passes; pass++) {
            boolean adjusted = false;
            for (WormholeGate gate : world.wormholes) {
                double minDistance = gate.radius + clearance;
                double dx = x - gate.x;
                double dy = y - gate.y;
                double dist = Math.hypot(dx, dy);
                if (dist >= minDistance) continue;
                if (dist < 1.0) {
                    dx = x - world.width * 0.5;
                    dy = y - world.height * 0.5;
                    dist = Math.hypot(dx, dy);
                    if (dist < 1.0) { dx = 1.0; dy = 0.0; dist = 1.0; }
                }
                x = Calc.clamp(gate.x + dx / dist * minDistance, clearance, world.width - clearance);
                y = Calc.clamp(gate.y + dy / dist * minDistance, clearance, world.height - clearance);
                adjusted = true;
            }
            if (!adjusted) break;
        }
        return new Point2D.Double(x, y);
    }

    private double unitClearance(Unit unit) { return 28.0 * unit.type().size.scale + WORMHOLE_EXIT_BUFFER; }
    private boolean wormholeExists(WorldSystemState state, String to) { for (WormholeGate gate : state.wormholes) if (gate.toSystemId.equals(to)) return true; return false; }

    private ResourceNode nthActiveResource(WorldSystemState state, Material material, int skip) {
        List<ResourceNode> active = new ArrayList<>();
        for (ResourceNode node : state.resources) if (node.active && node.material == material) active.add(node);
        if (active.isEmpty()) return null;
        return active.get(Math.floorMod(skip, active.size()));
    }

    private void loadActive(World world) {
        WorldSystemState state = active();
        if (state == null) return;
        world.systemTime = state.systemTime;
        world.resources.clear(); world.resources.addAll(state.resources);
        world.units.clear(); world.units.putAll(state.units);
        world.bases.clear(); world.bases.putAll(state.bases);
        world.shots.clear(); world.shots.addAll(state.shots);
        world.items.clear(); world.items.addAll(state.items);
        world.wormholes.clear(); world.wormholes.addAll(state.wormholes);
        world.width = state.width();
        world.height = state.height();
    }

    private WorldSystemState active() { return activeSystemId == null ? null : systems.get(activeSystemId); }
    private GalaxySystem asGalaxySystem(WorldSystemState state) { return new GalaxySystem(state.id, state.definition, state.systemTime, state.celestials); }
    private String playerHomeId(String playerId) { String clean = playerId == null || playerId.isBlank() ? "player" : playerId.replaceAll("[^A-Za-z0-9_-]", "_"); return StarSystems.PLAYER_HOME_SYSTEM_ID + "_" + clean; }

    private Map<String,Object> captureSystem(WorldSystemState state) {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("systemId", state.id);
        out.put("templateId", state.templateId);
        out.put("lifetime", state.lifetime.name());
        out.put("systemTime", state.systemTime);
        out.put("control", captureControl(state.control));
        out.put("wormholes", captureWormholes(state.wormholes));
        out.put("resources", captureResources(state.resources));
        out.put("units", captureUnits(state.units.values()));
        out.put("bases", captureBases(state.bases.values()));
        out.put("projectiles", captureShots(state.shots));
        out.put("worldItems", captureItems(state.items));
        return out;
    }

    private void restoreSystem(Map<String,Object> data) {
        String id = ServerSaveStore.string(data, "systemId", "");
        if (id.isBlank()) return;
        String templateId = SaveContentResolver.systemTemplateId(ServerSaveStore.string(data, "templateId", id));
        StarSystemDefinition definition = StarSystems.get(templateId);
        SystemLifetime lifetime = ServerSaveStore.enumValue(SystemLifetime.class, data.get("lifetime"), SystemLifetime.STATIC);
        WorldSystemState state = new WorldSystemState(id, templateId, definition, lifetime, "", new CelestialSystem(definition, new Random(seed ^ ((long)id.hashCode() << 21) ^ definition.id().hashCode())));
        state.systemTime = Math.max(0, ServerSaveStore.doubleValue(data, "systemTime", 0));
        if (state.systemTime > 0) state.celestials.update(state.systemTime);
        restoreControl(state.control, ServerSaveStore.object(data.get("control")));
        state.wormholes.addAll(restoreWormholes(ServerSaveStore.list(data.get("wormholes"))));
        state.resources.addAll(restoreResources(ServerSaveStore.list(data.get("resources"))));
        state.units.putAll(restoreUnits(ServerSaveStore.list(data.get("units"))));
        state.bases.putAll(restoreBases(ServerSaveStore.list(data.get("bases"))));
        state.shots.addAll(restoreShots(ServerSaveStore.list(data.get("projectiles"))));
        state.items.addAll(restoreItems(ServerSaveStore.list(data.get("worldItems"))));
        systems.put(id, state);
    }

    private Map<String,Object> captureControl(SystemControlState control) {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("controllerId", control.controllerId());
        out.put("claimantId", control.claimantId());
        out.put("status", control.status().name());
        out.put("captureProgress", control.captureProgress());
        out.put("changedAt", control.changedAt());
        return out;
    }

    private void restoreControl(SystemControlState control, Map<String,Object> data) {
        control.restore(
                ServerSaveStore.string(data, "controllerId", ""),
                ServerSaveStore.string(data, "claimantId", ""),
                ServerSaveStore.enumValue(SystemControlStatus.class, data.get("status"), SystemControlStatus.NEUTRAL),
                ServerSaveStore.doubleValue(data, "captureProgress", 0),
                ServerSaveStore.doubleValue(data, "changedAt", 0));
    }

    private List<Object> captureWormholes(List<WormholeGate> gates) {
        List<Object> out = new ArrayList<>();
        for (WormholeGate gate : gates) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id", gate.id); row.put("fromSystemId", gate.fromSystemId); row.put("toSystemId", gate.toSystemId);
            row.put("x", gate.x); row.put("y", gate.y); row.put("exitX", gate.exitX); row.put("exitY", gate.exitY);
            out.add(row);
        }
        return out;
    }

    private List<WormholeGate> restoreWormholes(List<Object> rows) {
        List<WormholeGate> out = new ArrayList<>();
        for (Object item : rows) {
            Map<String,Object> row = ServerSaveStore.object(item);
            out.add(new WormholeGate(ServerSaveStore.string(row, "id", ""), ServerSaveStore.string(row, "fromSystemId", ""),
                    ServerSaveStore.string(row, "toSystemId", ""), ServerSaveStore.doubleValue(row, "x", 0),
                    ServerSaveStore.doubleValue(row, "y", 0), ServerSaveStore.doubleValue(row, "exitX", 0),
                    ServerSaveStore.doubleValue(row, "exitY", 0)));
        }
        return out;
    }

    private List<Object> captureResources(List<ResourceNode> resources) {
        List<Object> out = new ArrayList<>();
        for (ResourceNode node : resources) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id", node.id); row.put("name", node.name); row.put("kind", node.kind.name()); row.put("material", node.material.name());
            row.put("x", node.x); row.put("y", node.y); row.put("amount", node.amount); row.put("maxAmount", node.maxAmount);
            row.put("harvestRate", node.harvestRate); row.put("radius", node.radius); row.put("respawnTimer", node.respawnTimer);
            row.put("active", node.active); row.put("orbiting", node.orbiting); row.put("orbitCenterX", node.orbitCenterX);
            row.put("orbitCenterY", node.orbitCenterY); row.put("orbitRadius", node.orbitRadius); row.put("orbitAngle", node.orbitAngle);
            row.put("orbitSpeed", node.orbitSpeed);
            out.add(row);
        }
        return out;
    }

    private List<ResourceNode> restoreResources(List<Object> rows) {
        List<ResourceNode> out = new ArrayList<>();
        for (Object item : rows) {
            Map<String,Object> row = ServerSaveStore.object(item);
            Material material = SaveContentResolver.material(ServerSaveStore.asString(row.get("material"), ""));
            ResourceNode node = new ResourceNode(ServerSaveStore.intValue(row, "id", 0), ServerSaveStore.string(row, "name", "Resource"),
                    SaveContentResolver.nodeKind(ServerSaveStore.asString(row.get("kind"), "")),
                    material == null ? Material.IRON : material,
                    ServerSaveStore.doubleValue(row, "x", 0), ServerSaveStore.doubleValue(row, "y", 0),
                    ServerSaveStore.doubleValue(row, "maxAmount", 0), ServerSaveStore.doubleValue(row, "harvestRate", 0),
                    ServerSaveStore.doubleValue(row, "radius", 20));
            node.amount = ServerSaveStore.doubleValue(row, "amount", node.maxAmount);
            node.respawnTimer = ServerSaveStore.doubleValue(row, "respawnTimer", 0);
            node.active = ServerSaveStore.boolValue(row, "active", true);
            node.orbiting = ServerSaveStore.boolValue(row, "orbiting", false);
            node.orbitCenterX = ServerSaveStore.doubleValue(row, "orbitCenterX", 0);
            node.orbitCenterY = ServerSaveStore.doubleValue(row, "orbitCenterY", 0);
            node.orbitRadius = ServerSaveStore.doubleValue(row, "orbitRadius", 0);
            node.orbitAngle = ServerSaveStore.doubleValue(row, "orbitAngle", 0);
            node.orbitSpeed = ServerSaveStore.doubleValue(row, "orbitSpeed", 0);
            out.add(node);
        }
        return out;
    }

    private List<Object> captureUnits(Collection<Unit> units) {
        List<Object> out = new ArrayList<>();
        for (Unit unit : units) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("playerId", unit.playerId); row.put("unitId", unit.unitId); row.put("shipTypeId", unit.shipTypeId);
            row.put("basePackageType", unit.basePackageType); row.put("attackTarget", unit.attackTarget);
            row.put("logisticsTargetBaseId", unit.logisticsTargetBaseId); row.put("logisticsRequestId", unit.logisticsRequestId);
            row.put("orderTarget", unit.orderTarget); row.put("task", unit.task.name()); row.put("orderType", unit.orderType.name());
            row.put("x", unit.x); row.put("y", unit.y); row.put("targetX", unit.targetX); row.put("targetY", unit.targetY);
            row.put("heading", unit.heading); row.put("orbitAngle", unit.orbitAngle); row.put("orbitRetarget", unit.orbitRetarget);
            row.put("weaponCooldown", unit.weaponCooldown); row.put("weaponFlashTimer", unit.weaponFlashTimer); row.put("wormholeCooldown", unit.wormholeCooldown);
            row.put("hp", unit.hp); row.put("shield", unit.shield); row.put("shieldDelayTimer", unit.shieldDelayTimer);
            row.put("miningAnchorX", unit.miningAnchorX); row.put("miningAnchorY", unit.miningAnchorY); row.put("automationResourceId", unit.automationResourceId);
            row.put("orderX1", unit.orderX1); row.put("orderY1", unit.orderY1); row.put("orderX2", unit.orderX2); row.put("orderY2", unit.orderY2);
            row.put("orderRadius", unit.orderRadius); row.put("orderPhase", unit.orderPhase); row.put("miningAnchorSet", unit.miningAnchorSet);
            row.put("inventory", ServerSaveStore.materialMap(unit.inventory));
            out.add(row);
        }
        return out;
    }

    private Map<String,Unit> restoreUnits(List<Object> rows) {
        Map<String,Unit> out = new LinkedHashMap<>();
        for (Object item : rows) {
            Map<String,Object> row = ServerSaveStore.object(item);
            Unit unit = new Unit(ServerSaveStore.string(row, "playerId", "SOLO"), ServerSaveStore.intValue(row, "unitId", 1),
                    SaveContentResolver.shipId(ServerSaveStore.string(row, "shipTypeId", Rules.STARTING_SHIP)), ServerSaveStore.doubleValue(row, "x", 0),
                    ServerSaveStore.doubleValue(row, "y", 0));
            unit.basePackageType = SaveContentResolver.optionalBaseId(ServerSaveStore.string(row, "basePackageType", ""));
            unit.attackTarget = ServerSaveStore.string(row, "attackTarget", "");
            unit.logisticsTargetBaseId = ServerSaveStore.string(row, "logisticsTargetBaseId", "");
            unit.logisticsRequestId = ServerSaveStore.string(row, "logisticsRequestId", "");
            unit.orderTarget = ServerSaveStore.string(row, "orderTarget", "");
            unit.task = ServerSaveStore.enumValue(UnitTask.class, row.get("task"), UnitTask.IDLE);
            unit.orderType = ServerSaveStore.enumValue(UnitOrderType.class, row.get("orderType"), UnitOrderType.NONE);
            unit.targetX = ServerSaveStore.doubleValue(row, "targetX", unit.x); unit.targetY = ServerSaveStore.doubleValue(row, "targetY", unit.y);
            unit.heading = ServerSaveStore.doubleValue(row, "heading", unit.heading); unit.orbitAngle = ServerSaveStore.doubleValue(row, "orbitAngle", unit.orbitAngle);
            unit.orbitRetarget = ServerSaveStore.doubleValue(row, "orbitRetarget", 0); unit.weaponCooldown = ServerSaveStore.doubleValue(row, "weaponCooldown", 0);
            unit.weaponFlashTimer = ServerSaveStore.doubleValue(row, "weaponFlashTimer", 0); unit.wormholeCooldown = ServerSaveStore.doubleValue(row, "wormholeCooldown", 0);
            unit.hp = ServerSaveStore.doubleValue(row, "hp", unit.hp); unit.shield = ServerSaveStore.doubleValue(row, "shield", unit.shield);
            unit.shieldDelayTimer = ServerSaveStore.doubleValue(row, "shieldDelayTimer", 0); unit.miningAnchorX = ServerSaveStore.doubleValue(row, "miningAnchorX", unit.x);
            unit.miningAnchorY = ServerSaveStore.doubleValue(row, "miningAnchorY", unit.y); unit.automationResourceId = ServerSaveStore.intValue(row, "automationResourceId", -1);
            unit.orderX1 = ServerSaveStore.doubleValue(row, "orderX1", 0); unit.orderY1 = ServerSaveStore.doubleValue(row, "orderY1", 0);
            unit.orderX2 = ServerSaveStore.doubleValue(row, "orderX2", 0); unit.orderY2 = ServerSaveStore.doubleValue(row, "orderY2", 0);
            unit.orderRadius = ServerSaveStore.doubleValue(row, "orderRadius", 0); unit.orderPhase = ServerSaveStore.intValue(row, "orderPhase", 0);
            unit.miningAnchorSet = ServerSaveStore.boolValue(row, "miningAnchorSet", false);
            unit.inventory.putAll(ServerSaveStore.restoreMaterialMap(row.get("inventory")));
            out.put(unit.key(), unit);
        }
        return out;
    }

    private List<Object> captureBases(Collection<Base> bases) {
        List<Object> out = new ArrayList<>();
        for (Base base : bases) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id", base.id); row.put("playerId", base.playerId); row.put("typeId", base.typeId);
            row.put("x", base.x); row.put("y", base.y); row.put("hp", base.hp); row.put("shield", base.shield);
            row.put("shieldDelayTimer", base.shieldDelayTimer); row.put("nextProductionJobId", base.nextProductionJobId);
            row.put("logisticsStatus", base.logisticsStatus); row.put("inventory", ServerSaveStore.materialMap(base.inventory));
            row.put("productionQueue", captureProduction(base.productionQueue));
            out.add(row);
        }
        return out;
    }

    private Map<String,Base> restoreBases(List<Object> rows) {
        Map<String,Base> out = new LinkedHashMap<>();
        for (Object item : rows) {
            Map<String,Object> row = ServerSaveStore.object(item);
            Base base = new Base(ServerSaveStore.string(row, "id", ""), ServerSaveStore.string(row, "playerId", "SOLO"),
                    SaveContentResolver.baseId(ServerSaveStore.string(row, "typeId", Rules.DEFAULT_BASE)), ServerSaveStore.doubleValue(row, "x", 0),
                    ServerSaveStore.doubleValue(row, "y", 0));
            base.hp = ServerSaveStore.doubleValue(row, "hp", base.hp); base.shield = ServerSaveStore.doubleValue(row, "shield", base.shield);
            base.shieldDelayTimer = ServerSaveStore.doubleValue(row, "shieldDelayTimer", 0);
            base.nextProductionJobId = Math.max(1, ServerSaveStore.longValue(row, "nextProductionJobId", 1));
            base.logisticsStatus = ServerSaveStore.string(row, "logisticsStatus", "");
            base.inventory.putAll(ServerSaveStore.restoreMaterialMap(row.get("inventory")));
            base.productionQueue.addAll(restoreProduction(ServerSaveStore.list(row.get("productionQueue"))));
            out.put(base.id, base);
        }
        return out;
    }

    private List<Object> captureProduction(List<ProductionJob> jobs) {
        List<Object> out = new ArrayList<>();
        for (ProductionJob job : jobs) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id", job.id); row.put("kind", job.kind.name()); row.put("itemId", job.itemId);
            row.put("duration", job.duration); row.put("remaining", job.remaining); row.put("resourcesReserved", job.resourcesReserved);
            row.put("reservedUnitKey", job.reservedUnitKey); row.put("blockedReason", job.blockedReason);
            out.add(row);
        }
        return out;
    }

    private List<ProductionJob> restoreProduction(List<Object> rows) {
        List<ProductionJob> out = new ArrayList<>();
        for (Object item : rows) {
            Map<String,Object> row = ServerSaveStore.object(item);
            ProductionJobKind kind = ServerSaveStore.enumValue(ProductionJobKind.class, row.get("kind"), ProductionJobKind.SHIP);
            String itemId = SaveContentResolver.productionItemId(kind, ServerSaveStore.string(row, "itemId", ""));
            if (itemId.isBlank()) continue;
            ProductionJob job = new ProductionJob(ServerSaveStore.string(row, "id", ""),
                    kind, itemId, ServerSaveStore.doubleValue(row, "duration", 0),
                    ServerSaveStore.doubleValue(row, "remaining", 0), ServerSaveStore.boolValue(row, "resourcesReserved", false),
                    ServerSaveStore.string(row, "reservedUnitKey", ""));
            job.blockedReason = ServerSaveStore.string(row, "blockedReason", "");
            out.add(job);
        }
        return out;
    }

    private List<Object> captureShots(List<ProjectileShot> shots) {
        List<Object> out = new ArrayList<>();
        for (ProjectileShot shot : shots) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id", shot.id); row.put("ownerId", shot.ownerId); row.put("weaponId", shot.weaponId); row.put("targetKey", shot.targetKey);
            row.put("x", shot.x); row.put("y", shot.y); row.put("lastX", shot.lastX); row.put("lastY", shot.lastY);
            out.add(row);
        }
        return out;
    }

    private List<ProjectileShot> restoreShots(List<Object> rows) {
        List<ProjectileShot> out = new ArrayList<>();
        for (Object item : rows) {
            Map<String,Object> row = ServerSaveStore.object(item);
            String weaponId = SaveContentResolver.weaponId(ServerSaveStore.string(row, "weaponId", ""));
            if (weaponId.isBlank()) continue;
            ProjectileShot shot = new ProjectileShot(ServerSaveStore.intValue(row, "id", 0), ServerSaveStore.string(row, "ownerId", ""),
                    weaponId, ServerSaveStore.string(row, "targetKey", ""),
                    ServerSaveStore.doubleValue(row, "x", 0), ServerSaveStore.doubleValue(row, "y", 0));
            shot.lastX = ServerSaveStore.doubleValue(row, "lastX", shot.x); shot.lastY = ServerSaveStore.doubleValue(row, "lastY", shot.y);
            out.add(shot);
        }
        return out;
    }

    private List<Object> captureItems(List<WorldItem> items) {
        List<Object> out = new ArrayList<>();
        for (WorldItem item : items) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id", item.id); row.put("material", item.material.name()); row.put("amount", item.amount);
            row.put("x", item.x); row.put("y", item.y); row.put("vx", item.vx); row.put("vy", item.vy);
            row.put("angle", item.angle); row.put("spin", item.spin);
            out.add(row);
        }
        return out;
    }

    private List<WorldItem> restoreItems(List<Object> rows) {
        List<WorldItem> out = new ArrayList<>();
        for (Object item : rows) {
            Map<String,Object> row = ServerSaveStore.object(item);
            Material material = SaveContentResolver.material(ServerSaveStore.asString(row.get("material"), ""));
            if (material == null) continue;
            out.add(new WorldItem(ServerSaveStore.intValue(row, "id", 0), material,
                    ServerSaveStore.doubleValue(row, "amount", 0), ServerSaveStore.doubleValue(row, "x", 0), ServerSaveStore.doubleValue(row, "y", 0),
                    ServerSaveStore.doubleValue(row, "vx", 0), ServerSaveStore.doubleValue(row, "vy", 0),
                    ServerSaveStore.doubleValue(row, "angle", 0), ServerSaveStore.doubleValue(row, "spin", 0)));
        }
        return out;
    }

    private Base movedBase(Base base, double dx, double dy, int width, int height) {
        Base moved = new Base(base.id, base.playerId, base.typeId, Calc.clamp(base.x + dx, 0, width), Calc.clamp(base.y + dy, 0, height));
        moved.hp = base.hp;
        moved.shield = base.shield;
        moved.shieldDelayTimer = base.shieldDelayTimer;
        moved.logisticsStatus = base.logisticsStatus;
        moved.inventory.putAll(base.inventory);
        return moved;
    }

    private void moveUnit(Unit unit, double dx, double dy, int width, int height) {
        unit.x = Calc.clamp(unit.x + dx, 0, width);
        unit.y = Calc.clamp(unit.y + dy, 0, height);
        unit.targetX = unit.x;
        unit.targetY = unit.y;
        unit.miningAnchorX = unit.x;
        unit.miningAnchorY = unit.y;
        unit.automationResourceId = -1;
        unit.attackTarget = "";
        unit.task = UnitTask.IDLE;
    }
}
