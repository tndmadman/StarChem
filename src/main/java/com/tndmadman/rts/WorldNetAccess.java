package com.tndmadman.rts;

import java.awt.geom.Point2D;
import java.util.*;

final class WorldNetAccess {
    private WorldNetAccess() { }

    static Snapshot snapshot(World world, long sequence) {
        List<PlayerInfo> players = PlayerRegistry.snapshotPlayers();
        List<UnitState> units = new ArrayList<>();
        for (Unit u : world.units.values()) units.add(new UnitState(u.playerId, u.unitId, u.shipTypeId, u.x, u.y, u.targetX, u.targetY, u.heading, u.task.name(), u.automationResourceId, u.basePackageType, CargoCodec.write(u.inventory), u.hp, u.attackTarget, u.weaponFlashTimer));
        List<ResourceState> resources = ResourceSync.snapshot(world);
        List<BaseState> bases = new ArrayList<>();
        for (Base b : world.bases.values()) bases.add(NetBaseSync.toState(b));
        List<StockState> stocks = List.of(new StockState(PlayerRegistry.localId(), CargoCodec.write(world.stockpile)));
        return new Snapshot(sequence, players, units, resources, bases, stocks);
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
        if (!snapshot.stocks().isEmpty()) CargoCodec.readInto(snapshot.stocks().get(0).cargo(), world.stockpile);
    }

    static void addPeerGroup(World world, String playerId) {
        int slot = slot(playerId);
        Point2D bp = resourceStart(world, slot);
        Point2D sp = new Point2D.Double(bp.getX() + 180, bp.getY() - 80);
        world.bases.put(playerId + ":B1", new Base(playerId + ":B1", playerId, Rules.DEFAULT_BASE, bp.getX(), bp.getY()));
        world.units.put(Unit.key(playerId, 1), new Unit(playerId, 1, Rules.STARTING_SHIP, sp.getX(), sp.getY()));
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
