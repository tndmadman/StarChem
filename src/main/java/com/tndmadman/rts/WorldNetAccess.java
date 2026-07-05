package com.tndmadman.rts;

import java.awt.geom.Point2D;
import java.util.*;

final class WorldNetAccess {
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
        return new Snapshot(sequence, players, units, resources, bases, stocks, shots);
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
        world.units.keySet().removeIf(key -> !liveUnits.contains(key));
        if (!snapshot.resources().isEmpty()) NetResourceSync.apply(world, snapshot.resources());
        if (!snapshot.bases().isEmpty()) {
            world.bases.clear();
            for (BaseState b : snapshot.bases()) world.bases.put(b.id(), NetBaseSync.fromState(b));
        }
        world.shots.clear();
        for (ShotState s : snapshot.shots()) {
            ProjectileShot shot = new ProjectileShot(s.id(), s.ownerId(), s.weaponId(), s.targetKey(), s.x(), s.y());
            shot.lastX = s.lastX();
            shot.lastY = s.lastY();
            world.shots.add(shot);
        }
        if (!snapshot.stocks().isEmpty()) CargoCodec.readInto(snapshot.stocks().get(0).cargo(), world.stockpile);
    }

    static void addPeerGroup(World world, String playerId) { spawnGroup(world, playerId, slot(playerId)); }

    static void respawnPlayer(World world, String playerId) {
        world.units.values().removeIf(unit -> unit.playerId.equals(playerId));
        world.bases.values().removeIf(base -> base.playerId.equals(playerId));
        world.shots.removeIf(shot -> shot.ownerId.equals(playerId));
        int salt = Math.max(5, world.units.size() + world.bases.size() + (int)Math.round(world.systemTime()));
        spawnGroup(world, playerId, slot(playerId) + salt);
        world.status = PlayerRegistry.name(playerId) + " respawned in a new sector.";
    }

    private static void spawnGroup(World world, String playerId, int slot) {
        Point2D bp = resourceStart(world, slot);
        Point2D sp = new Point2D.Double(bp.getX() + 180, bp.getY() - 80);
        int baseId = nextBaseNumber(world, playerId);
        int unitId = nextUnitNumber(world, playerId);
        world.bases.put(playerId + ":B" + baseId, new Base(playerId + ":B" + baseId, playerId, Rules.DEFAULT_BASE, bp.getX(), bp.getY()));
        world.units.put(Unit.key(playerId, unitId), new Unit(playerId, unitId, Rules.STARTING_SHIP, sp.getX(), sp.getY()));
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
        ResourceNode node = nthActiveResource(world, material, Math.floorMod(slot * 17, Math.max(1, world.resources.size())));
        if (node == null) return Calc.basePoint(slot);
        double cx = world.width / 2.0;
        double cy = world.height / 2.0;
        double a = Math.atan2(node.y - cy, node.x - cx);
        double r = Math.max(700, Math.hypot(node.x - cx, node.y - cy) - 260);
        return new Point2D.Double(cx + Math.cos(a) * r, cy + Math.sin(a) * r);
    }

    private static ResourceNode nthActiveResource(World world, Material material, int skip) {
        ResourceNode picked = null;
        int seen = 0;
        for (ResourceNode node : world.resources) {
            if (!node.active || node.material != material) continue;
            if (seen++ >= skip) return node;
            picked = node;
        }
        return picked;
    }

    private static int slot(String id) {
        if (id == null || id.equals("SOLO") || id.equals("HOST")) return 0;
        if (id.startsWith("P")) try { return Math.max(1, Integer.parseInt(id.substring(1))); } catch (NumberFormatException ignored) { }
        return Math.floorMod(id.hashCode(), 8);
    }
}
