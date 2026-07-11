package com.tndmadman.rts;

import java.util.*;

final class WorldNetAccess {
    private static final int SPAWN_SLOT_SEARCH = 64;

    private WorldNetAccess() { }

    static Snapshot snapshot(World world, long sequence) {
        List<PlayerInfo> players = new ArrayList<>();
        boolean includeSolo = hasWorldAssets(world, "SOLO");
        for (PlayerInfo player : PlayerRegistry.snapshotPlayers()) {
            if (realPlayerId(player.id()) || includeSolo && "SOLO".equals(player.id())) players.add(player);
        }
        List<UnitState> units = new ArrayList<>();
        for (Unit u : world.units.values()) units.add(new UnitState(u.playerId, u.unitId, u.shipTypeId, u.x, u.y, u.targetX, u.targetY, u.heading, u.task.name(), u.automationResourceId, u.basePackageType, CargoCodec.write(u.inventory), u.hp, u.shield, u.attackTarget, u.weaponFlashTimer, u.orderType.name(), u.orderX1, u.orderY1, u.orderX2, u.orderY2, u.orderRadius, u.orderTarget, u.orderPhase));
        List<ResourceState> resources = ResourceSync.snapshot(world);
        List<BaseState> bases = new ArrayList<>();
        for (Base b : world.bases.values()) bases.add(NetBaseSync.toState(b));
        List<StockState> stocks = List.of(new StockState(PlayerRegistry.localId(), CargoCodec.write(world.stockpile)));
        List<ShotState> shots = new ArrayList<>();
        for (ProjectileShot shot : world.shots) shots.add(new ShotState(shot.id, shot.ownerId, shot.weaponId, shot.targetKey, shot.x, shot.y, shot.lastX, shot.lastY));
        List<ItemState> items = new ArrayList<>();
        for (WorldItem item : world.items) items.add(new ItemState(item.id, item.material.name(), item.amount, item.x, item.y, item.vx, item.vy, item.angle, item.spin));
        return new Snapshot(sequence, players, units, resources, bases, stocks, shots, items, CelestialPacketCache.pack(world.activeSystemId()), world.systemTime());
    }

    static boolean hasPlayerAssets(Snapshot snapshot, String playerId) {
        if (playerId == null || playerId.isBlank()) return false;
        for (UnitState s : snapshot.units()) if (s.playerId().equals(playerId) && s.hp() > 0) return true;
        for (BaseState b : snapshot.bases()) if (b.playerId().equals(playerId) && b.hp() > 0) return true;
        return false;
    }

    static boolean usesPrimaryHome(String playerId) { return "P1".equals(playerId); }

    static void apply(World world, Snapshot snapshot) { apply(world, snapshot, false, false); }
    static void applyView(World world, Snapshot snapshot) { apply(world, snapshot, true, false); }
    static void applyFullView(World world, Snapshot snapshot) { apply(world, snapshot, true, true); }

    private static void apply(World world, Snapshot snapshot, boolean allowNoLocalAssets, boolean fullResourceView) {
        String local = PlayerRegistry.localId();
        String snapSystem = snapshot.systemId();
        boolean snapshotHasLocalAssets = hasPlayerAssets(snapshot, local);
        Map<String, Base> decodedBases = decodeBases(snapshot.bases(), snapSystem);
        ResourceNetDebug.worldApplyStart(world, snapshot, allowNoLocalAssets, fullResourceView, snapshotHasLocalAssets);
        if (fullResourceView && snapSystem != null && !snapSystem.isBlank() && !snapSystem.equals(world.activeSystemId())) {
            long seed = CelestialPacketCache.seed(world.systemSeed());
            double time = snapshot.systemTime() < 0 ? world.systemTime() : snapshot.systemTime();
            ResourceNetDebug.viewReset(world, snapSystem, seed, time);
            world.explosions.clear();
            ViewSnapshotReset.apply(world, snapSystem, seed, time);
        }
        if (snapSystem != null && !snapSystem.isBlank() && !snapSystem.equals(world.activeSystemId())) {
            world.status = "Ignoring stale snapshot for " + snapSystem + "; viewing " + world.activeSystemId() + ".";
            ResourceNetDebug.ignoredSnapshot(world, snapshot, "system mismatch");
            return;
        }
        for (PlayerInfo p : snapshot.players()) {
            if (!realPlayerId(p.id()) && !"SOLO".equals(p.id())) continue;
            if ("SOLO".equals(p.id()) && !"SOLO".equals(local)) continue;
            PlayerRegistry.register(p.id(), p.name(), p.rgb(), p.id().equals(local));
            world.ensurePlayerHome(p.id(), usesPrimaryHome(p.id()));
        }
        if (!allowNoLocalAssets && !snapshotHasLocalAssets && !local.equals("SOLO") && !local.equals("WAIT")) {
            world.ensurePlayerHome(local, usesPrimaryHome(local));
            world.activateSystem(world.playerHomeSystemId(local));
            if (noLocalFleet(world, local)) world.spawnPlayerGroup(local, separatedSlot(local, slot(local)));
            world.status = "Ignoring snapshot for another system; holding local fleet in " + world.activeSystemId() + ".";
            ResourceNetDebug.ignoredSnapshot(world, snapshot, "no local assets");
            return;
        }
        if (snapshot.systemTime() >= 0) {
            world.syncEnvironment(world.systemId(), world.systemSeed(), snapshot.systemTime());
            CelestialViewSync.apply(world, snapshot.systemId(), snapshot.systemTime());
        }
        boolean forceLocal = allowNoLocalAssets;
        Set<String> liveUnits = new HashSet<>();
        for (UnitState s : snapshot.units()) {
            String key = Unit.key(s.playerId(), s.unitId());
            liveUnits.add(key);
            Unit u = world.units.get(key);
            if (u == null) {
                u = new Unit(s.playerId(), s.unitId(), s.shipTypeId(), s.x(), s.y());
                world.units.put(key, u);
                if (PlayerRegistry.isLocal(u.playerId)) forceLocal = true;
            }
            SnapshotSmoother.apply(u, s, forceLocal);
        }
        Iterator<Map.Entry<String, Unit>> unitIt = world.units.entrySet().iterator();
        while (unitIt.hasNext()) {
            Map.Entry<String, Unit> entry = unitIt.next();
            if (!liveUnits.contains(entry.getKey())) unitIt.remove();
        }
        if (!snapshot.resources().isEmpty()) {
            if (fullResourceView) {
                ResourceNetDebug.resourceApplyPath(world, snapshot, "full-view-replace");
                ResourceViewSync.replace(world, snapshot.resources());
            } else if (allowNoLocalAssets) {
                ResourceNetDebug.resourceApplyPath(world, snapshot, "view-merge");
                ResourceViewSync.apply(world, snapshot.resources());
            } else {
                ResourceNetDebug.resourceApplyPath(world, snapshot, "regular-partial");
                NetResourceSync.apply(world, snapshot.resources());
            }
        }
        applyBases(world, decodedBases);
        world.shots.clear();
        for (ShotState s : snapshot.shots()) {
            ProjectileShot shot = new ProjectileShot(s.id(), s.ownerId(), s.weaponId(), s.targetKey(), s.x(), s.y());
            shot.lastX = s.lastX();
            shot.lastY = s.lastY();
            world.shots.add(shot);
        }
        ItemSync.apply(world, snapshot.items());
        if (!snapshot.stocks().isEmpty()) CargoCodec.readInto(snapshot.stocks().get(0).cargo(), world.stockpile);
        ResourceNetDebug.worldApplyEnd(world, snapshot);
    }

    private static boolean hasWorldAssets(World world, String playerId) {
        if (world == null || playerId == null || playerId.isBlank()) return false;
        for (Unit unit : world.units.values()) if (playerId.equals(unit.playerId) && unit.hp > 0) return true;
        for (Base base : world.bases.values()) if (playerId.equals(base.playerId) && base.hp > 0) return true;
        return false;
    }

    private static boolean noLocalFleet(World world, String local) {
        for (Unit unit : world.units.values()) if (unit.playerId.equals(local)) return false;
        for (Base base : world.bases.values()) if (base.playerId.equals(local)) return false;
        return true;
    }

    private static Map<String, Base> decodeBases(List<BaseState> states, String systemId) {
        Map<String, Base> decoded = new LinkedHashMap<>();
        for (BaseState state : states) {
            if (state == null || state.id() == null || state.id().isBlank()) {
                throw malformedBaseSnapshot(systemId, "base ID is required");
            }
            if (decoded.containsKey(state.id())) {
                throw malformedBaseSnapshot(systemId, "duplicate base ID " + state.id());
            }
            decoded.put(state.id(), NetBaseSync.fromState(state, systemId));
        }
        return decoded;
    }

    private static void applyBases(World world, Map<String, Base> decoded) {
        Set<String> live = decoded.keySet();
        Iterator<Base> it = world.bases.values().iterator();
        while (it.hasNext()) {
            Base base = it.next();
            if (live.contains(base.id)) continue;
            it.remove();
        }
        for (Map.Entry<String, Base> entry : decoded.entrySet()) world.bases.put(entry.getKey(), entry.getValue());
    }

    private static SnapshotDecodeException malformedBaseSnapshot(String systemId, String reason) {
        String location = systemId == null || systemId.isBlank() ? "" : " in system " + systemId;
        return new SnapshotDecodeException("Snapshot rejected: malformed base state" + location + " - " + reason + ".");
    }

    static void addPeerGroup(World world, String playerId) {
        if (!realPlayerId(playerId)) return;
        world.spawnPlayerGroup(playerId, separatedSlot(playerId, slot(playerId)), usesPrimaryHome(playerId));
    }

    static void respawnPlayer(World world, String playerId) {
        if (!realPlayerId(playerId) && !"SOLO".equals(playerId)) return;
        world.units.values().removeIf(unit -> unit.playerId.equals(playerId));
        world.bases.values().removeIf(base -> base.playerId.equals(playerId));
        world.shots.removeIf(shot -> shot.ownerId.equals(playerId));
        int salt = Math.max(5, world.units.size() + world.bases.size() + (int)Math.round(world.systemTime()));
        world.spawnPlayerGroup(playerId, separatedSlot(playerId, slot(playerId) + salt));
    }

    private static boolean realPlayerId(String id) { return id != null && !id.isBlank() && !"WAIT".equals(id) && !NpcRules.isNpcFaction(id); }
    private static int separatedSlot(String playerId, int preferredSlot) { return preferredSlot + Math.floorMod(playerId == null ? 0 : playerId.hashCode(), SPAWN_SLOT_SEARCH); }
    private static int slot(String id) { if (id == null || id.equals("SOLO") || id.equals("HOST")) return 0; if (id.startsWith("P")) try { return Math.max(1, Integer.parseInt(id.substring(1))); } catch (NumberFormatException ignored) { } return Math.floorMod(id.hashCode(), 8); }
}
