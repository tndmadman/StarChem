package com.tndmadman.rts;

import java.awt.geom.Point2D;
import java.util.*;

final class WorldNetAccess {
    private static final double MIN_PLAYER_START_DISTANCE = 1200.0;
    private static final double DEPLOYMENT_MATCH_DISTANCE = 48.0;
    private static final int SPAWN_SLOT_SEARCH = 64;

    private WorldNetAccess() { }

    static Snapshot snapshot(World world, long sequence) {
        List<PlayerInfo> players = PlayerRegistry.snapshotPlayers();
        List<UnitState> units = new ArrayList<>();
        for (Unit u : world.units.values()) units.add(new UnitState(u.playerId, u.unitId, u.shipTypeId, u.x, u.y, u.targetX, u.targetY, u.heading, u.task.name(), u.automationResourceId, u.basePackageType, CargoCodec.write(u.inventory), u.hp, u.shield, u.attackTarget, u.weaponFlashTimer));
        List<ResourceState> resources = ResourceSync.snapshot(world);
        List<BaseState> bases = new ArrayList<>();
        for (Base b : world.bases.values()) bases.add(NetBaseSync.toState(b));
        List<StockState> stocks = List.of(new StockState(PlayerRegistry.localId(), CargoCodec.write(world.stockpile)));
        List<ShotState> shots = new ArrayList<>();
        for (ProjectileShot shot : world.shots) shots.add(new ShotState(shot.id, shot.ownerId, shot.weaponId, shot.targetKey, shot.x, shot.y, shot.lastX, shot.lastY));
        List<ItemState> items = new ArrayList<>();
        for (WorldItem item : world.items) items.add(new ItemState(item.id, item.material.name(), item.amount, item.x, item.y, item.vx, item.vy, item.angle, item.spin));
        return new Snapshot(sequence, players, units, resources, bases, stocks, shots, items);
    }

    static void apply(World world, Snapshot snapshot) {
        String local = PlayerRegistry.localId();
        for (PlayerInfo p : snapshot.players()) PlayerRegistry.register(p.id(), p.name(), p.rgb(), p.id().equals(local));
        Set<String> liveUnits = new HashSet<>();
        for (UnitState s : snapshot.units()) {
            String key = Unit.key(s.playerId(), s.unitId());
            liveUnits.add(key);
            Unit u = world.units.get(key);
            if (u == null) {
                u = new Unit(s.playerId(), s.unitId(), s.shipTypeId(), s.x(), s.y());
                world.units.put(key, u);
            }
            SnapshotSmoother.apply(u, s);
        }
        Iterator<Map.Entry<String, Unit>> unitIt = world.units.entrySet().iterator();
        while (unitIt.hasNext()) {
            Map.Entry<String, Unit> entry = unitIt.next();
            if (!liveUnits.contains(entry.getKey())) {
                Unit unit = entry.getValue();
                if (!wasConvertedToBase(unit, snapshot.bases())) world.explodeUnit(unit);
                unitIt.remove();
            }
        }
        if (!snapshot.resources().isEmpty()) NetResourceSync.apply(world, snapshot.resources());
        if (!snapshot.bases().isEmpty()) applyBases(world, snapshot.bases());
        world.shots.clear();
        for (ShotState s : snapshot.shots()) {
            ProjectileShot shot = new ProjectileShot(s.id(), s.ownerId(), s.weaponId(), s.targetKey(), s.x(), s.y());
            shot.lastX = s.lastX();
            shot.lastY = s.lastY();
            world.shots.add(shot);
        }
        ItemSync.apply(world, snapshot.items());
        if (!snapshot.stocks().isEmpty()) CargoCodec.readInto(snapshot.stocks().get(0).cargo(), world.stockpile);
    }

    private static boolean wasConvertedToBase(Unit unit, List<BaseState> bases) {
        if (unit.basePackageType == null || unit.basePackageType.isBlank()) return false;
        for (BaseState base : bases) {
            if (!base.playerId().equals(unit.playerId)) continue;
            if (!base.typeId().equals(unit.basePackageType)) continue;
            if (Calc.distance(unit.x, unit.y, base.x(), base.y()) <= DEPLOYMENT_MATCH_DISTANCE) return true;
        }
        return false;
    }

    private static void applyBases(World world, List<BaseState> states) {
        Set<String> live = new HashSet<>();
        for (BaseState state : states) live.add(state.id());
        for (Base base : world.bases.values()) if (!live.contains(base.id)) world.explodeBase(base);
        world.bases.clear();
        for (BaseState b : states) world.bases.put(b.id(), NetBaseSync.fromState(b));
    }

    static void addPeerGroup(World world, String playerId) { spawnGroup(world, playerId, separatedSlot(world, slot(playerId))); }

    static void respawnPlayer(World world, String playerId) {
        world.units.values().removeIf(unit -> unit.playerId.equals(playerId));
        world.bases.values().removeIf(base -> base.playerId.equals(playerId));
        world.shots.removeIf(shot -> shot.ownerId.equals(playerId));
        int salt = Math.max(5, world.units.size() + world.bases.size() + (int)Math.round(world.systemTime()));
        spawnGroup(world, playerId, separatedSlot(world, slot(playerId) + salt));
        world.status = PlayerRegistry.name(playerId) + " respawned in a new sector.";
    }

    private static void spawnGroup(World world, String playerId, int slot) {
        Point2D bp = resourceStart(world, slot);
        Point2D sp = startShipPoint(bp);
        int baseId = nextBaseNumber(world, playerId);
        int unitId = nextUnitNumber(world, playerId);
        world.bases.put(playerId + ":B" + baseId, new Base(playerId + ":B" + baseId, playerId, Rules.DEFAULT_BASE, bp.getX(), bp.getY()));
        world.units.put(Unit.key(playerId, unitId), new Unit(playerId, unitId, Rules.STARTING_SHIP, sp.getX(), sp.getY()));
    }

    private static int separatedSlot(World world, int preferredSlot) {
        int bestSlot = preferredSlot;
        double bestDistance = -1;
        for (int offset = 0; offset < SPAWN_SLOT_SEARCH; offset++) {
            int candidateSlot = preferredSlot + offset;
            Point2D bp = resourceStart(world, candidateSlot);
            Point2D sp = startShipPoint(bp);
            double distance = nearestStartDistance(world, bp, sp);
            if (distance > bestDistance) {
                bestDistance = distance;
                bestSlot = candidateSlot;
            }
            if (distance >= MIN_PLAYER_START_DISTANCE) return candidateSlot;
        }
        return bestSlot;
    }

    private static Point2D startShipPoint(Point2D basePoint) {
        return new Point2D.Double(basePoint.getX() + 180, basePoint.getY() - 80);
    }

    private static double nearestStartDistance(World world, Point2D basePoint, Point2D shipPoint) {
        double nearest = Double.POSITIVE_INFINITY;
        for (Base base : world.bases.values()) nearest = nearestToStart(basePoint, shipPoint, base.x, base.y, nearest);
        for (Unit unit : world.units.values()) nearest = nearestToStart(basePoint, shipPoint, unit.x, unit.y, nearest);
        return nearest;
    }

    private static double nearestToStart(Point2D basePoint, Point2D shipPoint, double x, double y, double nearest) {
        double baseDistance = Calc.distance(basePoint.getX(), basePoint.getY(), x, y);
        double shipDistance = Calc.distance(shipPoint.getX(), shipPoint.getY(), x, y);
        return Math.min(nearest, Math.min(baseDistance, shipDistance));
    }

    private static int nextBaseNumber(World world, String playerId) {
        int max = 0;
        String prefix = playerId + ":B";
        for (String id : world.bases.keySet()) if (id.startsWith(prefix)) try { max = Math.max(max, Integer.parseInt(id.substring(prefix.length()))); } catch (NumberFormatException ignored) { }
        return max + 1;
    }

    private static int nextUnitNumber(World world, String playerId) {
        int max = 0;
        for (Unit unit : world.units.values()) if (unit.playerId.equals(playerId)) max = Math.max(max, unit.unitId);
        return max + 1;
    }

    private static Point2D resourceStart(World world, int slot) {
        Material material = switch (Math.floorMod(slot, 4)) {
            case 1 -> Material.IRON;
            case 2 -> Material.COPPER;
            case 3 -> Material.SILICATES;
            default -> Material.ICE;
        };
        ResourceNode node = nthActiveResource(world, material, slot * 17);
        if (node == null) return Calc.basePoint(slot);
        double cx = world.width / 2.0;
        double cy = world.height / 2.0;
        double a = Math.atan2(node.y - cy, node.x - cx);
        double r = Math.max(700, Math.hypot(node.x - cx, node.y - cy) - 260);
        return new Point2D.Double(cx + Math.cos(a) * r, cy + Math.sin(a) * r);
    }

    private static ResourceNode nthActiveResource(World world, Material material, int skip) {
        List<ResourceNode> active = new ArrayList<>();
        for (ResourceNode node : world.resources) if (node.active && node.material == material) active.add(node);
        if (active.isEmpty()) return null;
        return active.get(Math.floorMod(skip, active.size()));
    }

    private static int slot(String id) {
        if (id == null || id.equals("SOLO") || id.equals("HOST")) return 0;
        if (id.startsWith("P")) try { return Math.max(1, Integer.parseInt(id.substring(1))); } catch (NumberFormatException ignored) { }
        return Math.floorMod(id.hashCode(), 8);
    }
}
