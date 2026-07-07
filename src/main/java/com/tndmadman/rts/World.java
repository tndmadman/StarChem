package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.List;

final class World {
    final int width = 18000;
    final int height = 16000;
    final String localPlayerId = "SOLO";
    final String localPlayerName;
    final Color localColor = new Color(0x50BEFF);
    final List<ResourceNode> resources = new ArrayList<>();
    final Map<String, Unit> units = new LinkedHashMap<>();
    final Map<String, Base> bases = new LinkedHashMap<>();
    final List<ProjectileShot> shots = new ArrayList<>();
    final List<ExplosionEffect> explosions = new ArrayList<>();
    final List<WorldItem> items = new ArrayList<>();
    final EnumMap<Material, Double> stockpile = new EnumMap<>(Material.class);
    final Map<String, Set<String>> completedResearch = new LinkedHashMap<>();
    final LogisticsSystem logisticsSystem = new LogisticsSystem();
    private final Set<String> devFreeBuildPlayers = new LinkedHashSet<>();
    boolean devFreeBuild;

    private long systemSeed;
    private double systemTime;
    private Random random;
    private CelestialSystem celestials;
    private final WorkSystem workSystem = new WorkSystem();
    private final HaulerSystem haulerSystem = new HaulerSystem();
    private final ScoutSystem scoutSystem = new ScoutSystem();
    private final ResourceRespawnSystem resourceRespawnSystem = new ResourceRespawnSystem();
    private final BuildSystem buildSystem = new BuildSystem();
    private final WeaponSystem weaponSystem = new WeaponSystem();
    private final ItemPickupSystem itemPickupSystem = new ItemPickupSystem();
    private final NpcSystem npcSystem;
    private int nextUnitId = 1;
    private int nextBaseId = 1;
    private int nextShotId = 1;
    int nextWorldItemId = 1;
    int selectedResourceId = -1;
    String status = "Right-click a resource with a ship selected to auto-harvest.";

    World(String localPlayerName) { this(localPlayerName, Set.of()); }

    World(String localPlayerName, Set<String> disabledNpcFactionIds) {
        this.localPlayerName = Config.clean(localPlayerName);
        this.npcSystem = new NpcSystem(disabledNpcFactionIds);
        setSystemSeed(System.nanoTime() ^ System.currentTimeMillis());
        seedResources();
        Point2D basePoint = startBasePoint();
        addBase(Rules.DEFAULT_BASE, basePoint.getX(), basePoint.getY());
        Point2D start = startShipPoint(basePoint);
        spawnShip(Rules.STARTING_SHIP, start.getX(), start.getY());
    }

    long systemSeed() { return systemSeed; }
    double systemTime() { return systemTime; }

    void setDevFreeBuild(String playerId, boolean enabled) {
        if (playerId == null || playerId.isBlank()) return;
        if (enabled) devFreeBuildPlayers.add(playerId);
        else devFreeBuildPlayers.remove(playerId);
        if (PlayerRegistry.isLocal(playerId)) devFreeBuild = enabled;
    }

    boolean devFreeBuildFor(String playerId) { return playerId != null && devFreeBuildPlayers.contains(playerId); }

    boolean hasResearch(String playerId, String topicId) {
        if (playerId == null || topicId == null) return false;
        return completedResearch.getOrDefault(playerId, Set.of()).contains(topicId);
    }

    void completeResearch(String playerId, String topicId) {
        if (playerId == null || playerId.isBlank() || topicId == null || topicId.isBlank()) return;
        completedResearch.computeIfAbsent(playerId, ignored -> new LinkedHashSet<>()).add(topicId);
    }

    void useSystemSeed(long seed) { if (seed != systemSeed) syncEnvironment(seed, 0); }

    void syncEnvironment(long seed, double hostTime) {
        if (seed != systemSeed) {
            setSystemSeed(seed);
            resources.clear();
            seedResources();
        }
        double delta = hostTime - systemTime;
        if (Math.abs(delta) > 0.25) advanceEnvironment(delta);
    }

    private void setSystemSeed(long seed) {
        systemSeed = seed;
        systemTime = 0;
        random = new Random(seed);
        celestials = new CelestialSystem(width, height, random);
    }

    private void seedResources() { ResourceSpawner.seed(resources, celestials, random); }
    private Point2D startShipPoint(Point2D basePoint) { return new Point2D.Double(basePoint.getX() + 180, basePoint.getY() - 80); }

    private Point2D startBasePoint() {
        ResourceNode iron = firstResource(Material.IRON);
        if (iron == null) return new Point2D.Double(celestials.sunX() - 2550, celestials.sunY() + 900);
        double a = Math.atan2(iron.y - celestials.sunY(), iron.x - celestials.sunX());
        double r = Math.max(900, iron.orbitRadius - 260);
        return new Point2D.Double(celestials.sunX() + Math.cos(a) * r, celestials.sunY() + Math.sin(a) * r);
    }

    private ResourceNode firstResource(Material material) { for (ResourceNode node : resources) if (node.material == material) return node; return null; }

    void updateEnvironment(double dt) {
        advanceEnvironment(dt);
        updateItems(dt);
        updateExplosions(dt);
    }

    private void advanceEnvironment(double dt) {
        systemTime += dt;
        celestials.update(dt);
        ResourceSpawner.update(resources, celestials, dt);
    }

    private void updateItems(double dt) {
        Iterator<WorldItem> it = items.iterator();
        while (it.hasNext()) {
            WorldItem item = it.next();
            item.update(dt, width, height);
            if (item.empty()) it.remove();
        }
    }

    void update(double dt) {
        updateEnvironment(dt);
        resourceRespawnSystem.update(this, dt);
        StationFuelRules.consume(this, dt);
        logisticsSystem.update(this, dt);
        itemPickupSystem.update(this);
        scoutSystem.update(this);
        npcSystem.update(this, dt);
        for (Unit unit : new ArrayList<>(units.values())) updateUnit(unit, dt);
        weaponSystem.update(this, dt);
        cleanupDestroyed();
    }

    private void updateUnit(Unit unit, double dt) {
        unit.unloadingThisFrame = false;
        sendFullHarvestCargoToUnload(unit);
        autoUnload(unit, dt);
        haulerSystem.update(this, unit, dt);
        workSystem.update(this, unit, dt);
        if (unit.task == UnitTask.RETURN_TO_STATION) updateReturn(unit);
        if (unit.task == UnitTask.IDLE) idleNearBase(unit, dt);
        if (unit.task == UnitTask.MOVE && Calc.distance(unit.x, unit.y, unit.targetX, unit.targetY) < 5) unit.task = UnitTask.IDLE;
        unit.updatePosition(dt, width, height);
    }

    private void sendFullHarvestCargoToUnload(Unit unit) {
        if (unit.type().harvestKinds.isEmpty()) return;
        if (unit.task == UnitTask.RETURN_TO_STATION) return;
        if (unit.cargoUsed() <= 0.05 || unit.freeCargo() > 0.05) return;
        sendToNearestBase(unit);
    }

    private void updateReturn(Unit unit) {
        Base base = nearestBase(unit.playerId, unit.x, unit.y);
        Unit depot = MobileDepot.preferredFor(this, unit, base);
        if (base == null && depot == null) { unit.task = UnitTask.IDLE; return; }
        if (unit.cargoUsed() <= 0.05) {
            ResourceNode resume = findResource(unit.automationResourceId);
            if (resume != null && resume.active) unit.task = UnitTask.AUTO_HARVEST;
            else if (!returnToMiningAnchor(unit)) unit.task = UnitTask.IDLE;
            return;
        }
        if (depot != null) moveTowardOrbit(unit, depot.x, depot.y, MobileDepot.range(depot) * 0.55);
        else moveTowardOrbit(unit, base.x, base.y, base.type().unloadRange * 0.55);
    }

    private void idleNearBase(Unit unit, double dt) {
        Base base = nearestBase(unit.playerId, unit.x, unit.y);
        if (base != null && Calc.distance(unit.x, unit.y, base.x, base.y) < base.type().unloadRange + 170) orbitAround(unit, base.x, base.y, unit.type().idleOrbitRadius, dt, 0.35);
    }

    private void autoUnload(Unit unit, double dt) {
        if (FuelShuttleSystem.SHUTTLE_TYPE.equals(unit.shipTypeId) || LogisticsSystem.SHUTTLE_TYPE.equals(unit.shipTypeId)) return;
        if (unit.cargoUsed() <= 0.05) return;
        Base base = nearestBase(unit.playerId, unit.x, unit.y);
        Unit depot = MobileDepot.preferredFor(this, unit, base);
        if (MobileDepot.transfer(unit, depot, dt)) return;
        if (base == null || Calc.distance(unit.x, unit.y, base.x, base.y) > base.type().unloadRange) return;
        double remaining = Math.min(base.type().unloadRate * dt, unit.cargoUsed());
        for (Material material : Material.values()) {
            if (remaining <= 0.001) break;
            double held = unit.inventory.getOrDefault(material, 0.0);
            if (held <= 0.001) continue;
            double take = Math.min(held, remaining);
            unit.inventory.put(material, held - take);
            if (unit.inventory.getOrDefault(material, 0.0) <= 0.05) unit.inventory.remove(material);
            HangarStore.add(base.inventory, material, take);
            remaining -= take;
            unit.unloadingThisFrame = true;
        }
    }

    Base addBase(String type, double x, double y) { String id = "B" + nextBaseId++; Base base = new Base(id, localPlayerId, type, x, y); bases.put(id, base); return base; }
    Unit spawnShip(String type, double x, double y) { Unit unit = new Unit(localPlayerId, nextUnitId++, type, x, y); units.put(unit.key(), unit); return unit; }
    ProjectileShot addShot(String ownerId, String weaponId, String targetKey, double x, double y) { ProjectileShot shot = new ProjectileShot(nextShotId++, ownerId, weaponId, targetKey, x, y); shots.add(shot); return shot; }
    WorldItem addWorldItem(Material material, double amount, double x, double y, double vx, double vy, double angle, double spin) { WorldItem item = new WorldItem(nextWorldItemId++, material, amount, x, y, vx, vy, angle, spin); if (!item.empty()) items.add(item); return item.empty() ? null : item; }
    void explodeUnit(Unit unit) {
        if (unit != null) {
            explosions.add(ExplosionEffect.fromUnit(unit));
            ProceduralAudio.playDestruction(unit.type().size.scale);
        }
    }
    void explodeBase(Base base) {
        if (base != null) {
            explosions.add(ExplosionEffect.fromBase(base));
            ProceduralAudio.playDestruction(Math.max(2.0, base.type().maxHp / 900.0));
        }
    }

    private void updateExplosions(double dt) { Iterator<ExplosionEffect> it = explosions.iterator(); while (it.hasNext()) if (!it.next().update(dt)) it.remove(); }

    boolean buildShip(String baseId, String shipTypeId) { return buildSystem.buildShip(this, baseId, shipTypeId); }
    boolean loadBasePackage(String baseId, String packageType) { return buildSystem.loadBasePackage(this, baseId, packageType); }
    boolean placePackage(Unit unit) { return buildSystem.placePackage(this, unit); }
    boolean craftItem(String baseId, String craftableId) { return buildSystem.craftItem(this, baseId, craftableId); }
    boolean research(String baseId, String topicId) { return buildSystem.research(this, baseId, topicId); }

    void draw(Graphics2D g2) {
        drawMap(g2);
        celestials.draw(g2);
        for (Base base : bases.values()) base.draw(g2, localColor, stockpile, true);
        for (ResourceNode node : resources) node.draw(g2, node.id == selectedResourceId);
        for (WorldItem item : items) item.draw(g2);
        for (Unit unit : units.values()) {
            ResourceNode node = findResource(unit.automationResourceId);
            if (MiningBeam.visible(unit, node)) UnitRenderer.drawWorkLine(g2, unit, node);
            if (shouldDrawRoute(unit)) UnitRenderer.drawRoute(g2, unit, localColor);
        }
        weaponSystem.draw(g2, this);
        for (ExplosionEffect explosion : explosions) explosion.draw(g2);
        for (Unit unit : units.values()) UnitRenderer.draw(g2, unit, localColor, true);
    }

    private boolean shouldDrawRoute(Unit unit) { return PlayerRegistry.isLocal(unit.playerId) && (unit.task == UnitTask.MOVE || unit.task == UnitTask.RETURN_TO_STATION || unit.task == UnitTask.ATTACK); }

    private void drawMap(Graphics2D g2) {
        g2.setColor(new Color(9, 15, 24));
        g2.fillRect(0, 0, width, height);
        g2.setColor(new Color(22, 33, 48));
        for (int x = 0; x <= width; x += 160) g2.drawLine(x, 0, y: 0, x, height);
    }
}
