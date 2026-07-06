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
    private int nextUnitId = 1;
    private int nextBaseId = 1;
    private int nextShotId = 1;
    int nextWorldItemId = 1;
    int selectedResourceId = -1;
    String status = "Right-click a resource with a ship selected to auto-harvest.";

    World(String localPlayerName) {
        this.localPlayerName = Config.clean(localPlayerName);
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
        for (Unit unit : new ArrayList<>(units.values())) updateUnit(unit, dt);
        weaponSystem.update(this, dt);
        cleanupDestroyed();
    }

    private void updateUnit(Unit unit, double dt) {
        unit.unloadingThisFrame = false;
        autoUnload(unit, dt);
        haulerSystem.update(this, unit, dt);
        workSystem.update(this, unit, dt);
        if (unit.task == UnitTask.RETURN_TO_STATION) updateReturn(unit);
        if (unit.task == UnitTask.IDLE) idleNearBase(unit, dt);
        if (unit.task == UnitTask.MOVE && Calc.distance(unit.x, unit.y, unit.targetX, unit.targetY) < 5) unit.task = UnitTask.IDLE;
        unit.updatePosition(dt, width, height);
    }

    private void updateReturn(Unit unit) {
        Base base = nearestBase(unit.playerId, unit.x, unit.y);
        Unit depot = MobileDepot.preferredFor(this, unit, base);
        if (base == null && depot == null) { unit.task = UnitTask.IDLE; return; }
        if (unit.cargoUsed() <= 0.05) {
            ResourceNode resume = findResource(unit.automationResourceId);
            unit.task = resume != null && resume.active ? UnitTask.AUTO_HARVEST : UnitTask.IDLE;
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
        for (int x = 0; x <= width; x += 160) g2.drawLine(x, 0, x, height);
        for (int y = 0; y <= height; y += 160) g2.drawLine(0, y, width, y);
    }

    void selectAt(double x, double y) {
        ResourceNode node = resourceAt(x, y);
        if (node != null) { selectedResourceId = node.id; status = "Targeted " + node.name + ". Right-click to auto-harvest."; return; }
        Unit unit = unitAt(x, y);
        for (Unit u : units.values()) u.selected = false;
        if (unit != null && PlayerRegistry.isLocal(unit.playerId)) { unit.selected = true; status = "Selected " + unit.type().name + " #" + unit.unitId + "."; }
    }

    void selectBox(Rectangle2D box) { for (Unit unit : units.values()) unit.selected = PlayerRegistry.isLocal(unit.playerId) && box.contains(unit.x, unit.y); status = selectedCount() + " ship(s) selected."; }
    void moveSelected(double x, double y) { moveSelected(x, y, FleetFormation.GRID); }

    void moveSelected(double x, double y, FleetFormation formation) {
        List<Unit> selected = selectedUnits();
        if (selected.isEmpty()) { status = "No ship selected."; return; }
        for (int i = 0; i < selected.size(); i++) {
            Unit unit = selected.get(i);
            Point2D target = formationTarget(x, y, i, selected.size(), formation);
            unit.moveTo(target.getX(), target.getY());
        }
        status = "Moving " + selected.size() + " ship(s) in " + formation.label + " formation.";
    }

    private Point2D formationTarget(double x, double y, int index, int count, FleetFormation formation) {
        double spacing = 54;
        double ox = 0;
        double oy = 0;
        switch (formation) {
            case LINE -> ox = (index - (count - 1) / 2.0) * spacing;
            case COLUMN -> oy = (index - (count - 1) / 2.0) * spacing;
            case WEDGE -> {
                if (index > 0) {
                    int rank = (index + 1) / 2;
                    int side = index % 2 == 1 ? -1 : 1;
                    ox = side * rank * spacing;
                    oy = rank * spacing;
                }
            }
            case GRID -> {
                int cols = (int)Math.ceil(Math.sqrt(count));
                double rows = Math.ceil(count / (double)cols);
                int col = index % cols;
                int row = index / cols;
                ox = (col - (cols - 1) / 2.0) * 42;
                oy = (row - (rows - 1) / 2.0) * 42;
            }
        }
        return new Point2D.Double(Calc.clamp(x + ox, 0, width), Calc.clamp(y + oy, 0, height));
    }

    void attackSelected(String targetKey) {
        int started = 0;
        int unarmed = 0;
        for (Unit unit : selectedUnits()) {
            if (!WeaponRules.armed(unit.type())) { unarmed++; continue; }
            if (!CombatTarget.enemy(this, unit, targetKey)) continue;
            unit.attack(targetKey);
            started++;
        }
        if (started > 0) status = "Attacking target with " + started + " ship(s).";
        else status = unarmed > 0 ? "Selected ship has no weapons." : "No valid attack target.";
    }

    void autoHarvestSelected(ResourceNode node) { int started = 0; for (Unit unit : selectedUnits()) if (unit.type().harvestKinds.contains(node.kind)) { unit.startAutoHarvest(node.id); started++; } status = started == 0 ? "Selected ship cannot harvest this node." : "Auto-harvesting " + node.name + "."; }

    void sendToNearestBase(Unit unit) {
        Base base = nearestBase(unit.playerId, unit.x, unit.y);
        Unit depot = MobileDepot.preferredFor(this, unit, base);
        if (base == null && depot == null) return;
        unit.task = UnitTask.RETURN_TO_STATION;
        if (depot != null) moveTowardOrbit(unit, depot.x, depot.y, MobileDepot.range(depot) * 0.55);
        else moveTowardOrbit(unit, base.x, base.y, base.type().unloadRange * 0.55);
    }

    boolean scoutRetarget(Unit unit, ResourceNode oldNode) { return oldNode != null && scoutSystem.retargetAfterDepletion(this, unit, oldNode); }

    void orbitAround(Unit unit, double cx, double cy, double radius, double dt, double speed) {
        unit.orbitAngle += dt * speed * (unit.unitId % 2 == 0 ? 1 : -1);
        unit.targetX = Calc.clamp(cx + Math.cos(unit.orbitAngle) * radius, 0, width);
        unit.targetY = Calc.clamp(cy + Math.sin(unit.orbitAngle) * radius, 0, height);
        unit.orbitRetarget = 0;
    }

    void moveTowardOrbit(Unit unit, double cx, double cy, double radius) {
        double angle = Math.atan2(unit.y - cy, unit.x - cx);
        if (Double.isNaN(angle)) angle = unit.unitId;
        unit.targetX = Calc.clamp(cx + Math.cos(angle) * radius, 0, width);
        unit.targetY = Calc.clamp(cy + Math.sin(angle) * radius, 0, height);
    }

    void relocateResource(ResourceNode node) { ResourceSpawner.relocate(node, resources, bases.values(), celestials, random); }

    private void cleanupDestroyed() {
        Iterator<Unit> unitIt = units.values().iterator();
        while (unitIt.hasNext()) {
            Unit unit = unitIt.next();
            if (unit.hp <= 0) {
                dropLoot(unit);
                explodeUnit(unit);
                unitIt.remove();
            }
        }
        Iterator<Base> baseIt = bases.values().iterator();
        while (baseIt.hasNext()) {
            Base base = baseIt.next();
            if (base.hp <= 0) {
                dropLoot(base);
                explodeBase(base);
                baseIt.remove();
            }
        }
        shots.removeIf(shot -> !CombatTarget.alive(this, shot.targetKey) || shot.weapon() == null);
        for (Unit unit : units.values()) {
            if (!unit.attackTarget.isBlank() && !CombatTarget.alive(this, unit.attackTarget)) {
                unit.attackTarget = "";
                if (unit.task == UnitTask.ATTACK) unit.task = UnitTask.IDLE;
            }
        }
    }

    private void dropLoot(Unit unit) {
        int count = WorldLootDrops.scatter(this, SalvageDrops.fromUnit(unit), unit.x, unit.y, Math.max(1.0, unit.type().size.scale), lootSeed(unit.key(), unit.x, unit.y));
        if (count > 0 && PlayerRegistry.isLocal(unit.playerId)) status = "Destroyed ship dropped cargo and salvage.";
    }

    private void dropLoot(Base base) {
        double power = Math.max(2.4, base.type().maxHp / 900.0);
        int count = WorldLootDrops.scatter(this, SalvageDrops.fromBase(base), base.x, base.y, power, lootSeed(base.id, base.x, base.y));
        if (count > 0 && PlayerRegistry.isLocal(base.playerId)) status = "Destroyed station dropped hangar loot and salvage.";
    }

    private long lootSeed(String key, double x, double y) {
        return System.nanoTime() ^ ((long)key.hashCode() << 32) ^ Double.doubleToLongBits(x * 37.0 + y * 41.0);
    }

    ResourceNode resourceAt(double x, double y) { ResourceNode best = null; double bestDist = Double.MAX_VALUE; for (ResourceNode node : resources) if (node.active) { double d = Calc.distance(x, y, node.x, node.y); if (d <= node.radius + 14 && d < bestDist) { best = node; bestDist = d; } } return best; }
    Base baseAt(double x, double y) { for (Base base : bases.values()) if (base.contains(x, y)) return base; return null; }
    Unit unitAt(double x, double y) { for (Unit unit : units.values()) if (unit.contains(x, y)) return unit; return null; }
    ResourceNode findResource(int id) { for (ResourceNode node : resources) if (node.id == id) return node; return null; }
    Base nearestBase(double x, double y) { return nearestBase(PlayerRegistry.localId(), x, y); }
    Base nearestBase(String playerId, double x, double y) { Base best = null; double bestDist = Double.MAX_VALUE; for (Base base : bases.values()) if (base.playerId.equals(playerId)) { double d = Calc.distance(x, y, base.x, base.y); if (d < bestDist) { best = base; bestDist = d; } } return best; }
    List<Unit> selectedUnits() { List<Unit> out = new ArrayList<>(); for (Unit unit : units.values()) if (unit.selected && PlayerRegistry.isLocal(unit.playerId)) out.add(unit); return out; }
    Unit selectedUnit() { for (Unit unit : units.values()) if (unit.selected && PlayerRegistry.isLocal(unit.playerId)) return unit; return null; }
    int selectedCount() { int count = 0; for (Unit unit : units.values()) if (unit.selected && PlayerRegistry.isLocal(unit.playerId)) count++; return count; }

    boolean canAfford(List<Cost> cost) { for (Cost c : cost) if (stockpile.getOrDefault(c.material(), 0.0) + 0.001 < c.amount()) return false; return true; }
    void spend(List<Cost> cost) { for (Cost c : cost) { double next = stockpile.getOrDefault(c.material(), 0.0) - c.amount(); if (next <= 0.05) stockpile.remove(c.material()); else stockpile.put(c.material(), next); } }

    Rectangle2D localBounds() {
        boolean found = false;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Unit u : units.values()) if (PlayerRegistry.isLocal(u.playerId)) { found = true; minX = Math.min(minX, u.x); minY = Math.min(minY, u.y); maxX = Math.max(maxX, u.x); maxY = Math.max(maxY, u.y); }
        for (Base b : bases.values()) if (PlayerRegistry.isLocal(b.playerId)) { found = true; minX = Math.min(minX, b.x); minY = Math.min(minY, b.y); maxX = Math.max(maxX, b.x); maxY = Math.max(maxY, b.y); }
        return found ? new Rectangle2D.Double(minX, minY, Math.max(1, maxX - minX), Math.max(1, maxY - minY)) : null;
    }
}
