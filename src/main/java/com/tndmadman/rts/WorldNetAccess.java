package com.tndmadman.rts;

import java.util.*;

final class WorldNetAccess {
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
        return new Snapshot(sequence, players, units, resources, bases, stocks, shots, items, world.systemTime());
    }

    static boolean hasPlayerAssets(Snapshot snapshot, String playerId) {
        if (playerId == null || playerId.isBlank()) return false;
        for (UnitState s : snapshot.units()) if (s.playerId().equals(playerId) && s.hp() > 0) return true;
        for (BaseState b : snapshot.bases()) if (b.playerId().equals(playerId) && b.hp() > 0) return true;
        return false;
    }

    static void apply(World world, Snapshot snapshot) {
        apply(world, snapshot, false);
    }

    static void applyView(World world, Snapshot snapshot) {
        apply(world, snapshot, true);
    }

    private static void apply(World world, Snapshot snapshot, boolean allowNoLocalAssets) {
        String local = PlayerRegistry.localId();
        boolean snapshotHasLocalAssets = hasPlayerAssets(snapshot, local);
        for (PlayerInfo p : snapshot.players()) {
            PlayerRegistry.register(p.id(), p.name(), p.rgb(), p.id().equals(local));
            if (!NpcRules.isNpcFaction(p.id())) world.ensurePlayerHome(p.id());
        }
        if (!allowNoLocalAssets && !snapshotHasLocalAssets && !local.equals("SOLO") && !local.equals("WAIT")) {
            world.ensurePlayerHome(local);
            world.activateSystem(world.playerHomeSystemId(local));
            if (noLocalFleet(world, local)) world.spawnPlayerGroup(local, separatedSlot(local, slot(local)));
            world.status = "Ignoring snapshot for another system; holding local fleet in " + world.activeSystemId() + ".";
            return;
        }
        if (snapshot.systemTime() >= 0) world.syncEnvironment(world.systemId(), world.systemSeed(), snapshot.systemTime());
        boolean explodeMissing = !allowNoLocalAssets;
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
                if (explodeMissing && !wasConvertedToBase(unit, snapshot.bases())) world.explodeUnit(unit);
                unitIt.remove();
            }
        }
        if (!snapshot.resources().isEmpty()) NetResourceSync.apply(world, snapshot.resources());
        applyBases(world, snapshot.bases(), explodeMissing);
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

    private static boolean noLocalFleet(World world, String local) {
        for (Unit unit : world.units.values()) if (unit.playerId.equals(local)) return false;
        for (Base base : world.bases.values()) if (base.playerId.equals(local)) return false;
        return true;
    }

    private static boolean wasConvertedToBase(Unit unit, List<BaseState> bases) {
        if (unit.basePackageType == null || unit.basePackageType.isBlank()) return false;
        for (BaseState base : bases) {
            if (!base.playerId().equals(unit.playerId)) continue;
            if (!base.typeId().equals(unit.basePackageType)) continue;
            if (Calc.distance(unit.x, unit.y, base.x(), base.y()) <= 48.0) return true;
        }
        return false;
    }

    private static void applyBases(World world, List<BaseState> states, boolean explodeMissing) {
        Set<String> live = new HashSet<>();
        for (BaseState state : states) live.add(state.id());
        Iterator<Base> it = world.bases.values().iterator();
        while (it.hasNext()) {
            Base base = it.next();
            if (live.contains(base.id)) continue;
            if (explodeMissing) world.explodeBase(base);
            it.remove();
        }
        for (BaseState b : states) world.bases.put(b.id(), NetBaseSync.fromState(b));
    }

    static void addPeerGroup(World world, String playerId) {
        world.ensurePlayerHome(playerId);
        world.spawnPlayerGroup(playerId, separatedSlot(playerId, slot(playerId)));
    }

    static void respawnPlayer(World world, String playerId) {
        world.units.values().removeIf(unit -> unit.playerId.equals(playerId));
        world.bases.values().removeIf(base -> base.playerId.equals(playerId));
        world.shots.removeIf(shot -> shot.ownerId.equals(playerId));
        int salt = Math.max(5, world.units.size() + world.bases.size() + (int)Math.round(world.systemTime()));
        world.spawnPlayerGroup(playerId, separatedSlot(playerId, slot(playerId) + salt));
        world.status = PlayerRegistry.name(playerId) + " respawned in a home system.";
    }

    private static int separatedSlot(String playerId, int preferredSlot) { return preferredSlot + Math.floorMod(playerId == null ? 0 : playerId.hashCode(), SPAWN_SLOT_SEARCH); }
    private static int slot(String id) { if (id == null || id.equals("SOLO") || id.equals("HOST")) return 0; if (id.startsWith("P")) try { return Math.max(1, Integer.parseInt(id.substring(1))); } catch (NumberFormatException ignored) { } return Math.floorMod(id.hashCode(), 8); }
}
