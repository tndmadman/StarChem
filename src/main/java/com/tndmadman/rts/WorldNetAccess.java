package com.tndmadman.rts;

import java.awt.geom.Point2D;
import java.util.*;

final class WorldNetAccess {
    private WorldNetAccess() { }

    static Snapshot snapshot(World world, long sequence) {
        List<PlayerInfo> players = PlayerRegistry.snapshotPlayers();
        List<UnitState> units = new ArrayList<>();
        for (Unit u : world.units.values()) units.add(new UnitState(u.playerId, u.unitId, u.shipTypeId, u.x, u.y, u.targetX, u.targetY, u.heading, u.task.name(), u.automationResourceId, u.basePackageType, CargoCodec.write(u.inventory)));
        List<ResourceState> resources = new ArrayList<>();
        for (ResourceNode r : world.resources) resources.add(new ResourceState(r.id, r.name, r.kind.name(), r.material.name(), r.x, r.y, r.maxAmount, r.harvestRate, r.radius, r.amount, r.active, r.respawnTimer));
        List<BaseState> bases = new ArrayList<>();
        for (Base b : world.bases.values()) bases.add(new BaseState(b.id, b.playerId, b.typeId, b.x, b.y));
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
            for (BaseState b : snapshot.bases()) world.bases.put(b.id(), new Base(b.id(), b.playerId(), b.typeId(), b.x(), b.y()));
        }
        if (!snapshot.stocks().isEmpty()) CargoCodec.readInto(snapshot.stocks().get(0).cargo(), world.stockpile);
    }

    static void addPeerGroup(World world, String playerId) {
        Point2D bp = Calc.basePoint(slot(playerId));
        Point2D sp = Calc.spawnPoint(slot(playerId));
        world.bases.put(playerId + ":B1", new Base(playerId + ":B1", playerId, Rules.DEFAULT_BASE, bp.getX(), bp.getY()));
        world.units.put(Unit.key(playerId, 1), new Unit(playerId, 1, Rules.STARTING_SHIP, sp.getX(), sp.getY()));
    }

    private static int slot(String id) {
        if (id == null || id.equals("SOLO") || id.equals("HOST")) return 0;
        if (id.startsWith("P")) try { return Math.max(1, Integer.parseInt(id.substring(1))); } catch (NumberFormatException ignored) { }
        return Math.floorMod(id.hashCode(), 8);
    }
}
