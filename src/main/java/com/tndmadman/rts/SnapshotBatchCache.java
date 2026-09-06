package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-thread, per-broadcast cache for snapshot state that is identical for every viewer.
 * Resource rows deliberately remain outside this cache because ResourceSync carries
 * viewer-specific tombstone/context semantics.
 */
final class SnapshotBatchCache {
    private static final ThreadLocal<Map<Key, CommonState>> ACTIVE = new ThreadLocal<>();

    private SnapshotBatchCache() { }

    static void begin() {
        ACTIVE.set(new HashMap<>());
    }

    static void end() {
        ACTIVE.remove();
    }

    static CommonState common(World world) {
        Map<Key, CommonState> cache = ACTIVE.get();
        if (cache == null || world == null) return null;
        Key key = new Key(world, world.activeSystemId());
        return cache.computeIfAbsent(key, ignored -> build(world));
    }

    private static CommonState build(World world) {
        List<UnitState> units = new ArrayList<>(world.units.size());
        for (Unit u : world.units.values()) {
            units.add(new UnitState(u.playerId, u.unitId, u.shipTypeId, u.x, u.y, u.targetX, u.targetY,
                    u.heading, u.task.name(), u.automationResourceId, u.basePackageType,
                    CargoCodec.write(u.inventory), u.hp, u.shield, u.attackTarget, u.weaponFlashTimer,
                    u.orderType.name(), u.orderX1, u.orderY1, u.orderX2, u.orderY2, u.orderRadius,
                    u.orderTarget, u.orderPhase, u.loadoutId));
        }
        List<BaseState> bases = new ArrayList<>(world.bases.size());
        for (Base b : world.bases.values()) bases.add(NetBaseSync.toState(b));
        List<ShotState> shots = new ArrayList<>(world.shots.size());
        for (ProjectileShot shot : world.shots) {
            shots.add(new ShotState(shot.id, shot.ownerId, shot.weaponId, shot.targetKey,
                    shot.x, shot.y, shot.lastX, shot.lastY));
        }
        List<ItemState> items = new ArrayList<>(world.items.size());
        for (WorldItem item : world.items) {
            items.add(new ItemState(item.id, item.material.name(), item.amount, item.x, item.y,
                    item.vx, item.vy, item.angle, item.spin));
        }
        return new CommonState(List.copyOf(units), List.copyOf(bases), List.copyOf(shots), List.copyOf(items));
    }

    record CommonState(List<UnitState> units, List<BaseState> bases, List<ShotState> shots, List<ItemState> items) { }

    private record Key(World world, String systemId) {
        @Override public int hashCode() {
            return System.identityHashCode(world) * 31 + (systemId == null ? 0 : systemId.hashCode());
        }
        @Override public boolean equals(Object other) {
            return other instanceof Key key && key.world == world
                    && (systemId == null ? key.systemId == null : systemId.equals(key.systemId));
        }
    }
}
