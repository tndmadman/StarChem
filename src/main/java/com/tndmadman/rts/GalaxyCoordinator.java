package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.Point2D;
import java.util.*;
import java.util.List;

final class GalaxyCoordinator {
    private static final double SYSTEM_PADDING = 3200;
    private final List<GalaxySystem> systems = new ArrayList<>();
    private final Map<String, String> playerHomes = new LinkedHashMap<>();
    private final Map<String, Double> jumpCooldowns = new HashMap<>();
    private long seed;
    private int nextResourceId = 1;
    private int nextEmptySystem = 1;

    CelestialSystem rebuild(World world, StarSystemDefinition primary, long seed) {
        this.seed = seed;
        systems.clear();
        playerHomes.clear();
        jumpCooldowns.clear();
        world.resources.clear();
        world.wormholes.clear();
        nextResourceId = 1;
        nextEmptySystem = 1;
        GalaxySystem main = addSystem(world, primary.id(), primary);
        GalaxySystem corsairs = addSystem(world, StarSystems.CORSAIR_SYSTEM_ID, StarSystems.get(StarSystems.CORSAIR_SYSTEM_ID));
        link(world, main, corsairs);
        recomputeWorldSize(world);
        return main.celestials;
    }

    GalaxySystem ensurePlayerHome(World world, String playerId, StarSystemDefinition primary) {
        if (playerId == null || playerId.isBlank()) playerId = world.localPlayerId;
        String existing = playerHomes.get(playerId);
        GalaxySystem found = existing == null ? null : system(existing);
        if (found != null) return found;
        if (world.localPlayerId.equals(playerId)) {
            GalaxySystem home = system(primary.id());
            if (home == null) home = addSystem(world, primary.id(), primary);
            playerHomes.put(playerId, home.id);
            return home;
        }
        String id = StarSystems.PLAYER_HOME_SYSTEM_ID + "_" + nextEmptySystem++;
        GalaxySystem home = addSystem(world, id, StarSystems.get(StarSystems.PLAYER_HOME_SYSTEM_ID));
        playerHomes.put(playerId, home.id);
        GalaxySystem main = systems.isEmpty() ? home : systems.get(0);
        link(world, main, home);
        recomputeWorldSize(world);
        return home;
    }

    List<Material> spawnMaterials(World world, String playerId, StarSystemDefinition primary) {
        return ensurePlayerHome(world, playerId, primary).definition.spawnMaterials();
    }

    Point2D startPoint(World world, String playerId, int slot, StarSystemDefinition primary) {
        GalaxySystem home = ensurePlayerHome(world, playerId, primary);
        List<Material> materials = home.definition.spawnMaterials();
        Material material = materials.isEmpty() ? Material.IRON : materials.get(Math.floorMod(slot, materials.size()));
        ResourceNode node = nthActiveResource(world, home, material, slot * 17);
        if (node == null) return home.point(home.width() * 0.32, home.height() * 0.56);
        double a = Math.atan2(node.y - home.celestials.sunY(), node.x - home.celestials.sunX());
        double r = Math.max(700, Math.hypot(node.x - home.celestials.sunX(), node.y - home.celestials.sunY()) - 260);
        return new Point2D.Double(home.celestials.sunX() + Math.cos(a) * r, home.celestials.sunY() + Math.sin(a) * r);
    }

    Point2D npcSpawnPoint(World world, String factionId, double padding) {
        GalaxySystem target;
        if (Config.CORSAIRS_ID.equals(factionId)) {
            target = system(StarSystems.CORSAIR_SYSTEM_ID);
            if (target == null) target = addSystem(world, StarSystems.CORSAIR_SYSTEM_ID, StarSystems.get(StarSystems.CORSAIR_SYSTEM_ID));
        } else {
            target = systems.isEmpty() ? null : systems.get(0);
        }
        if (target == null) return new Point2D.Double(world.width / 2.0, world.height / 2.0);
        return target.point(Math.max(padding, target.width() * 0.34), Math.max(padding, target.height() * 0.52));
    }

    void update(World world, double dt) {
        for (GalaxySystem system : systems) system.celestials.update(dt);
        updateJumps(world, dt);
    }

    void draw(Graphics2D g2) {
        for (GalaxySystem system : systems) system.celestials.draw(g2);
        for (WormholeGate gate : wormholes()) gate.draw(g2);
    }

    void drawMap(Graphics2D g2, int width, int height) {
        g2.setColor(new Color(9, 15, 24));
        g2.fillRect(0, 0, width, height);
        g2.setColor(new Color(22, 33, 48));
        for (int x = 0; x <= width; x += 160) g2.drawLine(x, 0, x, height);
        for (int y = 0; y <= height; y += 160) g2.drawLine(0, y, width, y);
        g2.setStroke(new BasicStroke(3f));
        for (GalaxySystem system : systems) {
            g2.setColor(new Color(80, 145, 210, 80));
            g2.drawRect((int)system.offsetX, (int)system.offsetY, (int)system.width(), (int)system.height());
            g2.setColor(new Color(220, 238, 250, 190));
            g2.drawString(system.definition.name() + " [" + system.id + "]", (int)system.offsetX + 24, (int)system.offsetY + 32);
        }
    }

    Base nearestBaseInSameSystem(World world, String playerId, double x, double y) {
        GalaxySystem here = systemAt(x, y);
        Base best = null;
        double bestDist = Double.MAX_VALUE;
        for (Base base : world.bases.values()) {
            if (!base.playerId.equals(playerId)) continue;
            if (here != null && !here.contains(base.x, base.y)) continue;
            double d = Calc.distance(x, y, base.x, base.y);
            if (d < bestDist) { best = base; bestDist = d; }
        }
        return best;
    }

    private List<WormholeGate> wormholes() { return CurrentWorldHolder.wormholes; }

    private GalaxySystem addSystem(World world, String instanceId, StarSystemDefinition definition) {
        GalaxySystem existing = system(instanceId);
        if (existing != null) return existing;
        double offsetX = systems.isEmpty() ? 0 : systems.get(systems.size() - 1).maxX() + SYSTEM_PADDING;
        Random systemRandom = new Random(seed ^ ((long)instanceId.hashCode() << 21) ^ definition.id().hashCode());
        CelestialSystem celestials = new CelestialSystem(definition, systemRandom, offsetX, 0);
        GalaxySystem system = new GalaxySystem(instanceId, definition, offsetX, 0, celestials);
        systems.add(system);
        nextResourceId = ResourceSpawner.seed(world.resources, celestials, systemRandom, nextResourceId, definition.resourceBelts());
        recomputeWorldSize(world);
        return system;
    }

    private void link(World world, GalaxySystem a, GalaxySystem b) {
        if (a == null || b == null || wormholeExists(world, a.id, b.id)) return;
        double ax = a.offsetX + a.width() * 0.78;
        double ay = a.offsetY + a.height() * 0.52;
        double bx = b.offsetX + b.width() * 0.22;
        double by = b.offsetY + b.height() * 0.52;
        world.wormholes.add(new WormholeGate(a.id + "_to_" + b.id, a.id, b.id, ax, ay, bx + 180, by));
        world.wormholes.add(new WormholeGate(b.id + "_to_" + a.id, b.id, a.id, bx, by, ax - 180, ay));
    }

    private boolean wormholeExists(World world, String from, String to) {
        for (WormholeGate gate : world.wormholes) if (gate.fromSystemId.equals(from) && gate.toSystemId.equals(to)) return true;
        return false;
    }

    private ResourceNode nthActiveResource(World world, GalaxySystem system, Material material, int skip) {
        List<ResourceNode> active = new ArrayList<>();
        for (ResourceNode node : world.resources) {
            if (node.active && node.material == material && system.contains(node.x, node.y)) active.add(node);
        }
        if (active.isEmpty()) return null;
        return active.get(Math.floorMod(skip, active.size()));
    }

    private void updateJumps(World world, double dt) {
        Iterator<Map.Entry<String, Double>> cooldownIt = jumpCooldowns.entrySet().iterator();
        while (cooldownIt.hasNext()) {
            Map.Entry<String, Double> entry = cooldownIt.next();
            double next = entry.getValue() - dt;
            if (next <= 0) cooldownIt.remove(); else entry.setValue(next);
        }
        for (Unit unit : world.units.values()) {
            if (unit.hp <= 0 || jumpCooldowns.containsKey(unit.key())) continue;
            for (WormholeGate gate : world.wormholes) {
                if (!gate.contains(unit.x, unit.y)) continue;
                unit.x = gate.exitX;
                unit.y = gate.exitY;
                unit.targetX = gate.exitX + 120;
                unit.targetY = gate.exitY;
                unit.automationResourceId = -1;
                unit.attackTarget = "";
                unit.task = UnitTask.IDLE;
                jumpCooldowns.put(unit.key(), 3.0);
                if (PlayerRegistry.isLocal(unit.playerId)) world.status = "Gate jump complete: " + gate.toSystemId + ".";
                break;
            }
        }
    }

    private GalaxySystem system(String id) {
        for (GalaxySystem system : systems) if (system.id.equals(id)) return system;
        return null;
    }

    private GalaxySystem systemAt(double x, double y) {
        for (GalaxySystem system : systems) if (system.contains(x, y)) return system;
        return null;
    }

    private void recomputeWorldSize(World world) {
        double maxX = 1, maxY = 1;
        for (GalaxySystem system : systems) {
            maxX = Math.max(maxX, system.maxX());
            maxY = Math.max(maxY, system.maxY());
        }
        world.width = (int)Math.ceil(maxX + 800);
        world.height = (int)Math.ceil(maxY + 800);
    }

    private static final class CurrentWorldHolder {
        private static final List<WormholeGate> wormholes = List.of();
    }
}
