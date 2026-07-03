package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.List;

final class World {
    final int width = 2200;
    final int height = 1400;
    final String localPlayerId = "SOLO";
    final String localPlayerName;
    final Color localColor = new Color(0x50BEFF);
    final List<ResourceNode> resources = new ArrayList<>();
    final Map<String, Unit> units = new LinkedHashMap<>();
    final Map<String, Base> bases = new LinkedHashMap<>();
    final EnumMap<Material, Double> stockpile = new EnumMap<>(Material.class);

    private final Random random = new Random(1977);
    private final WorkSystem workSystem = new WorkSystem();
    private final ScoutSystem scoutSystem = new ScoutSystem();
    private final ResourceRespawnSystem resourceRespawnSystem = new ResourceRespawnSystem();
    private final BuildSystem buildSystem = new BuildSystem();

    private int nextUnitId = 1;
    private int nextBaseId = 1;
    int selectedResourceId = -1;
    String status = "Right-click a resource with a ship selected to auto-harvest.";

    World(String localPlayerName) {
        this.localPlayerName = Config.clean(localPlayerName);
        seedResources();
        Point2D basePoint = Calc.basePoint(0);
        addBase(Rules.DEFAULT_BASE, basePoint.getX(), basePoint.getY());
        Point2D start = Calc.spawnPoint(0);
        spawnShip(Rules.STARTING_SHIP, start.getX(), start.getY());
    }

    private void seedResources() {
        resources.add(new ResourceNode(1, "Iron Vein", NodeKind.SILICATE_ROCK, Material.IRON, 620, 370, 260, 7.5, 32));
        resources.add(new ResourceNode(2, "Copper Vein", NodeKind.SILICATE_ROCK, Material.COPPER, 1010, 610, 180, 6.5, 28));
        resources.add(new ResourceNode(3, "Ice Rock", NodeKind.SILICATE_ROCK, Material.ICE, 1380, 330, 220, 7.0, 31));
        resources.add(new ResourceNode(4, "Silicate Cluster", NodeKind.SILICATE_ROCK, Material.SILICATES, 1660, 1000, 320, 8.0, 36));
        resources.add(new ResourceNode(5, "Hydrogen Cloud", NodeKind.GAS_CLOUD, Material.HYDROGEN, 890, 980, 360, 9.0, 58));
        resources.add(new ResourceNode(6, "Helium Pocket", NodeKind.GAS_CLOUD, Material.HELIUM, 1830, 520, 210, 7.5, 52));
        resources.add(new ResourceNode(7, "Methane Cloud", NodeKind.GAS_CLOUD, Material.METHANE, 420, 1060, 240, 7.5, 55));
        resources.add(new ResourceNode(8, "Ammonia Trace", NodeKind.GAS_CLOUD, Material.AMMONIA, 1250, 1130, 180, 6.5, 50));
    }

    void update(double dt) {
        resourceRespawnSystem.update(this, dt);
        scoutSystem.update(this);
        for (Unit unit : new ArrayList<>(units.values())) updateUnit(unit, dt);
    }

    private void updateUnit(Unit unit, double dt) {
        unit.unloadingThisFrame = false;
        autoUnload(unit, dt);
        workSystem.update(this, unit, dt);
        if (unit.task == UnitTask.RETURN_TO_STATION) updateReturn(unit);
        if (unit.task == UnitTask.IDLE) idleNearBase(unit, dt);
        if (unit.task == UnitTask.MOVE && Calc.distance(unit.x, unit.y, unit.targetX, unit.targetY) < 5) unit.task = UnitTask.IDLE;
        unit.updatePosition(dt, width, height);
    }

    private void updateReturn(Unit unit) {
        Base base = nearestBase(unit.x, unit.y);
        if (base == null) {
            unit.task = UnitTask.IDLE;
            return;
        }
        if (unit.cargoUsed() <= 0.05) {
            ResourceNode resume = findResource(unit.automationResourceId);
            unit.task = resume != null && resume.active ? UnitTask.AUTO_HARVEST : UnitTask.IDLE;
            return;
        }
        moveTowardOrbit(unit, base.x, base.y, base.type().unloadRange * 0.55);
    }

    private void idleNearBase(Unit unit, double dt) {
        Base base = nearestBase(unit.x, unit.y);
        if (base != null && Calc.distance(unit.x, unit.y, base.x, base.y) < base.type().unloadRange + 170) {
            orbitAround(unit, base.x, base.y, unit.type().idleOrbitRadius, dt, 0.35);
        }
    }

    private void autoUnload(Unit unit, double dt) {
        if (unit.cargoUsed() <= 0.05) return;
        Base base = nearestBase(unit.x, unit.y);
        if (base == null || Calc.distance(unit.x, unit.y, base.x, base.y) > base.type().unloadRange) return;
        double remaining = Math.min(base.type().unloadRate * dt, unit.cargoUsed());
        for (Material material : Material.values()) {
            if (remaining <= 0.001) break;
            double held = unit.inventory.getOrDefault(material, 0.0);
            if (held <= 0.001) continue;
            double take = Math.min(held, remaining);
            unit.inventory.put(material, held - take);
            if (unit.inventory.getOrDefault(material, 0.0) <= 0.05) unit.inventory.remove(material);
            stockpile.put(material, stockpile.getOrDefault(material, 0.0) + take);
            remaining -= take;
            unit.unloadingThisFrame = true;
        }
    }

    Base addBase(String type, double x, double y) {
        String id = "B" + nextBaseId++;
        Base base = new Base(id, localPlayerId, type, x, y);
        bases.put(id, base);
        return base;
    }

    Unit spawnShip(String type, double x, double y) {
        Unit unit = new Unit(localPlayerId, nextUnitId++, type, x, y);
        units.put(unit.key(), unit);
        return unit;
    }

    boolean buildShip(String baseId, String shipTypeId) { return buildSystem.buildShip(this, baseId, shipTypeId); }
    boolean loadBasePackage(String baseId, String packageType) { return buildSystem.loadBasePackage(this, baseId, packageType); }
    boolean placePackage(Unit unit) { return buildSystem.placePackage(this, unit); }

    void draw(Graphics2D g2) {
        drawMap(g2);
        for (Base base : bases.values()) base.draw(g2, localColor, stockpile, true);
        for (ResourceNode node : resources) node.draw(g2, node.id == selectedResourceId);
        for (Unit unit : units.values()) {
            ResourceNode node = findResource(unit.automationResourceId);
            if (unit.task == UnitTask.AUTO_HARVEST && node != null && node.active) UnitRenderer.drawWorkLine(g2, unit, node);
            UnitRenderer.drawRoute(g2, unit, localColor);
        }
        for (Unit unit : units.values()) UnitRenderer.draw(g2, unit, localColor, true);
    }

    private void drawMap(Graphics2D g2) {
        g2.setColor(new Color(9, 15, 24));
        g2.fillRect(0, 0, width, height);
        g2.setColor(new Color(22, 33, 48));
        for (int x = 0; x <= width; x += 80) g2.drawLine(x, 0, x, height);
        for (int y = 0; y <= height; y += 80) g2.drawLine(0, y, width, y);
    }

    void selectAt(double x, double y) {
        ResourceNode node = resourceAt(x, y);
        if (node != null) {
            selectedResourceId = node.id;
            status = "Targeted " + node.name + ". Right-click to auto-harvest.";
            return;
        }
        Unit unit = unitAt(x, y);
        for (Unit u : units.values()) u.selected = false;
        if (unit != null) {
            unit.selected = true;
            status = "Selected " + unit.type().name + " #" + unit.unitId + ".";
        }
    }

    void selectBox(Rectangle2D box) {
        for (Unit unit : units.values()) unit.selected = box.contains(unit.x, unit.y);
        status = selectedCount() + " ship(s) selected.";
    }

    void moveSelected(double x, double y) {
        List<Unit> selected = selectedUnits();
        if (selected.isEmpty()) {
            status = "No ship selected.";
            return;
        }
        int count = selected.size();
        int cols = (int)Math.ceil(Math.sqrt(count));
        double rows = Math.ceil(count / (double)cols);
        double spacing = 42;
        double centerCol = (cols - 1) / 2.0;
        double centerRow = (rows - 1) / 2.0;
        for (int i = 0; i < count; i++) {
            Unit unit = selected.get(i);
            int col = i % cols;
            int row = i / cols;
            unit.moveTo(x + (col - centerCol) * spacing, y + (row - centerRow) * spacing);
        }
    }

    void autoHarvestSelected(ResourceNode node) {
        int started = 0;
        for (Unit unit : selectedUnits()) {
            if (unit.type().harvestKinds.contains(node.kind)) {
                unit.startAutoHarvest(node.id);
                started++;
            }
        }
        status = started == 0 ? "Selected ship cannot harvest this node." : "Auto-harvesting " + node.name + ".";
    }

    void sendToNearestBase(Unit unit) {
        Base base = nearestBase(unit.x, unit.y);
        if (base == null) return;
        unit.task = UnitTask.RETURN_TO_STATION;
        moveTowardOrbit(unit, base.x, base.y, base.type().unloadRange * 0.55);
    }

    void orbitAround(Unit unit, double cx, double cy, double radius, double dt, double speed) {
        unit.orbitAngle += dt * speed * (unit.unitId % 2 == 0 ? 1 : -1);
        unit.orbitRetarget -= dt;
        if (unit.orbitRetarget <= 0 || Calc.distance(unit.x, unit.y, unit.targetX, unit.targetY) < 12) {
            unit.targetX = Calc.clamp(cx + Math.cos(unit.orbitAngle) * radius, 0, width);
            unit.targetY = Calc.clamp(cy + Math.sin(unit.orbitAngle) * radius, 0, height);
            unit.orbitRetarget = 1.1;
        }
    }

    void moveTowardOrbit(Unit unit, double cx, double cy, double radius) {
        double angle = Math.atan2(unit.y - cy, unit.x - cx);
        if (Double.isNaN(angle)) angle = unit.unitId;
        unit.targetX = Calc.clamp(cx + Math.cos(angle) * radius, 0, width);
        unit.targetY = Calc.clamp(cy + Math.sin(angle) * radius, 0, height);
    }

    void relocateResource(ResourceNode node) {
        for (int attempt = 0; attempt < 120; attempt++) {
            double x = 140 + random.nextDouble() * (width - 280);
            double y = 140 + random.nextDouble() * (height - 280);
            if (validResourceSpot(node.id, x, y)) {
                node.x = x;
                node.y = y;
                node.amount = node.maxAmount;
                node.active = true;
                node.respawnTimer = 0;
                return;
            }
        }
        node.x = width / 2.0;
        node.y = height / 2.0;
        node.amount = node.maxAmount;
        node.active = true;
    }

    private boolean validResourceSpot(int nodeId, double x, double y) {
        for (Base base : bases.values()) if (Calc.distance(x, y, base.x, base.y) < 180) return false;
        for (ResourceNode node : resources) if (node.id != nodeId && node.active && Calc.distance(x, y, node.x, node.y) < 140) return false;
        return true;
    }

    ResourceNode resourceAt(double x, double y) {
        ResourceNode best = null;
        double bestDist = Double.MAX_VALUE;
        for (ResourceNode node : resources) if (node.active) {
            double d = Calc.distance(x, y, node.x, node.y);
            if (d <= node.radius + 14 && d < bestDist) { best = node; bestDist = d; }
        }
        return best;
    }

    Base baseAt(double x, double y) { for (Base base : bases.values()) if (base.contains(x, y)) return base; return null; }
    Unit unitAt(double x, double y) { for (Unit unit : units.values()) if (unit.contains(x, y)) return unit; return null; }
    ResourceNode findResource(int id) { for (ResourceNode node : resources) if (node.id == id) return node; return null; }
    Base nearestBase(double x, double y) { Base best = null; double bestDist = Double.MAX_VALUE; for (Base base : bases.values()) { double d = Calc.distance(x, y, base.x, base.y); if (d < bestDist) { best = base; bestDist = d; } } return best; }
    List<Unit> selectedUnits() { List<Unit> out = new ArrayList<>(); for (Unit unit : units.values()) if (unit.selected) out.add(unit); return out; }
    Unit selectedUnit() { for (Unit unit : units.values()) if (unit.selected) return unit; return null; }
    int selectedCount() { int count = 0; for (Unit unit : units.values()) if (unit.selected) count++; return count; }

    boolean canAfford(List<Cost> cost) { for (Cost c : cost) if (stockpile.getOrDefault(c.material(), 0.0) + 0.001 < c.amount()) return false; return true; }
    void spend(List<Cost> cost) { for (Cost c : cost) { double next = stockpile.getOrDefault(c.material(), 0.0) - c.amount(); if (next <= 0.05) stockpile.remove(c.material()); else stockpile.put(c.material(), next); } }

    Rectangle2D localBounds() {
        boolean found = false;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Unit u : units.values()) { found = true; minX = Math.min(minX, u.x); minY = Math.min(minY, u.y); maxX = Math.max(maxX, u.x); maxY = Math.max(maxY, u.y); }
        for (Base b : bases.values()) { found = true; minX = Math.min(minX, b.x); minY = Math.min(minY, b.y); maxX = Math.max(maxX, b.x); maxY = Math.max(maxY, b.y); }
        return found ? new Rectangle2D.Double(minX, minY, Math.max(1, maxX - minX), Math.max(1, maxY - minY)) : null;
    }
}
