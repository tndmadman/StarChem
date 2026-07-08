package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.Point2D;
import java.util.*;
import java.util.List;

final class GalaxyCoordinator {
    private final Map<String, WorldSystemState> systems = new LinkedHashMap<>();
    private final Map<String, String> playerHomes = new LinkedHashMap<>();
    private String activeSystemId;
    private long seed;
    private int nextResourceId = 1;

    CelestialSystem rebuild(World world, StarSystemDefinition primary, long seed) {
        this.seed = seed;
        systems.clear();
        playerHomes.clear();
        world.resources.clear();
        world.units.clear();
        world.bases.clear();
        world.shots.clear();
        world.items.clear();
        world.wormholes.clear();
        nextResourceId = 1;
        WorldSystemState main = createSystem(primary.id(), primary);
        WorldSystemState corsairs = createSystem(StarSystems.CORSAIR_SYSTEM_ID, StarSystems.get(StarSystems.CORSAIR_SYSTEM_ID));
        linkAllKnown(world, main);
        linkAllKnown(world, corsairs);
        activeSystemId = main.id;
        loadActive(world);
        return main.celestials;
    }

    String activeSystemId() { return activeSystemId; }
    CelestialSystem activeCelestials() { WorldSystemState state = active(); return state == null ? null : state.celestials; }
    double activeSystemTime() { WorldSystemState state = active(); return state == null ? 0 : state.systemTime; }
    void setActiveSystemTime(double time) { WorldSystemState state = active(); if (state != null) state.systemTime = Math.max(0, time); }

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

    GalaxySystem ensurePlayerHome(World world, String playerId, StarSystemDefinition primary) {
        if (playerId == null || playerId.isBlank()) playerId = world.localPlayerId;
        String existing = playerHomes.get(playerId);
        if (existing != null && systems.containsKey(existing)) {
            WorldSystemState home = systems.get(existing);
            linkAllKnown(world, home);
            return asGalaxySystem(home);
        }
        WorldSystemState home;
        if (world.localPlayerId.equals(playerId)) {
            home = systems.get(primary.id());
            if (home == null) home = createSystem(primary.id(), primary);
        } else home = createSystem(playerHomeId(playerId), StarSystems.get(StarSystems.PLAYER_HOME_SYSTEM_ID));
        playerHomes.put(playerId, home.id);
        linkAllKnown(world, home);
        return asGalaxySystem(home);
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
        WorldSystemState state = Config.CORSAIRS_ID.equals(factionId) ? systems.get(StarSystems.CORSAIR_SYSTEM_ID) : active();
        if (state == null) return new Point2D.Double(world.width / 2.0, world.height / 2.0);
        return new Point2D.Double(Math.max(padding, state.width() * 0.34), Math.max(padding, state.height() * 0.52));
    }

    void update(World world, double dt) {
        WorldSystemState state = active();
        if (state == null || dt == 0) return;
        state.systemTime += dt;
        state.celestials.update(dt);
    }

    void updateInactiveSystems(double dt) {
        if (dt == 0) return;
        for (WorldSystemState state : systems.values()) {
            if (state == null || state.id.equals(activeSystemId)) continue;
            state.systemTime += dt;
            state.celestials.update(dt);
            for (ResourceNode node : state.resources) node.updateOrbit(state.celestials.sunX(), state.celestials.sunY(), dt);
        }
    }
    void draw(World world, Graphics2D g2) { WorldSystemState state = active(); if (state != null) state.celestials.draw(g2); for (WormholeGate gate : world.wormholes) gate.draw(g2); }

    void drawMap(Graphics2D g2, int width, int height) {
        g2.setColor(new Color(9, 15, 24));
        g2.fillRect(0, 0, width, height);
        g2.setColor(new Color(22, 33, 48));
        for (int x = 0; x <= width; x += 160) g2.drawLine(x, 0, x, height);
        for (int y = 0; y <= height; y += 160) g2.drawLine(0, y, width, y);
        WorldSystemState state = active();
        if (state != null) { g2.setColor(new Color(220, 238, 250, 190)); g2.drawString(state.definition.name() + " [" + state.id + "]", 24, 32); }
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

    boolean transferTouchingShips(World world) {
        List<Unit> unitsToMove = new ArrayList<>(world.units.values());
        boolean moved = false;
        for (Unit unit : unitsToMove) {
            WormholeGate gate = touchingGate(world, unit);
            if (gate == null) continue;
            moved |= transferUnit(world, gate, unit);
        }
        return moved;
    }

    private WormholeGate touchingGate(World world, Unit unit) { for (WormholeGate gate : world.wormholes) if (gate.contains(unit.x, unit.y)) return gate; return null; }

    private boolean transferUnit(World world, WormholeGate gate, Unit unit) {
        WorldSystemState from = active();
        WorldSystemState to = systems.get(gate.toSystemId);
        if (from == null || to == null || unit == null) return false;
        if (!world.units.containsKey(unit.key())) return false;
        String previous = activeSystemId;
        world.units.remove(unit.key());
        saveActive(world);
        activeSystemId = to.id;
        loadActive(world);
        unit.x = Calc.clamp(gate.exitX, 0, world.width);
        unit.y = Calc.clamp(gate.exitY, 0, world.height);
        unit.targetX = unit.x;
        unit.targetY = unit.y;
        unit.automationResourceId = -1;
        unit.attackTarget = "";
        unit.task = UnitTask.IDLE;
        world.units.put(unit.key(), unit);
        saveActive(world);
        activeSystemId = previous;
        loadActive(world);
        world.status = unit.type().name + " entered wormhole to " + to.definition.name() + ".";
        return true;
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

    private WorldSystemState createSystem(String id, StarSystemDefinition definition) {
        WorldSystemState existing = systems.get(id);
        if (existing != null) return existing;
        Random systemRandom = new Random(seed ^ ((long)id.hashCode() << 21) ^ definition.id().hashCode());
        CelestialSystem celestials = new CelestialSystem(definition, systemRandom);
        WorldSystemState state = new WorldSystemState(id, definition, celestials);
        nextResourceId = ResourceSpawner.seed(state.resources, celestials, systemRandom, nextResourceId, definition.resourceBelts());
        systems.put(id, state);
        return state;
    }

    private void linkAllKnown(World world, WorldSystemState state) {
        if (state == null) return;
        for (WorldSystemState other : new ArrayList<>(systems.values())) link(world, state, other);
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
