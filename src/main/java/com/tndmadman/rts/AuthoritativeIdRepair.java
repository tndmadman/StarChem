package com.tndmadman.rts;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.ListIterator;
import java.util.Set;

/** Repairs persisted numeric IDs before malformed authoritative state reaches the wire. */
final class AuthoritativeIdRepair {
    private static final Field NEXT_SHOT_ID = field(World.class, "nextShotId");

    private AuthoritativeIdRepair() { }

    static boolean canRepair(SnapshotDecodeException failure) {
        String message = failure == null ? "" : failure.getMessage();
        return message != null && (message.contains("malformed items") || message.contains("malformed shots"));
    }

    static void prepareWorldItems(World world) {
        if (world == null) return;
        world.nextWorldItemId = Math.max(world.nextWorldItemId, nextItemId(world));
    }

    static RepairStats repairActive(World world) {
        if (world == null) return new RepairStats(0, 0);
        int repairedItems = repairItems(world);
        int repairedShots = repairShots(world);
        if (repairedItems > 0 || repairedShots > 0) {
            System.err.println("[SERVER][STATE-REPAIR] Reassigned duplicate or invalid IDs in system "
                    + world.activeSystemId() + ": items=" + repairedItems + ", projectiles=" + repairedShots + '.');
        }
        return new RepairStats(repairedItems, repairedShots);
    }

    private static int repairItems(World world) {
        Set<Integer> used = new HashSet<>();
        int next = nextItemId(world);
        int repaired = 0;
        ListIterator<WorldItem> iterator = world.items.listIterator();
        while (iterator.hasNext()) {
            WorldItem item = iterator.next();
            if (item == null) continue;
            if (item.id >= 0 && used.add(item.id)) continue;
            int replacementId = available(used, next, "world item");
            WorldItem replacement = new WorldItem(replacementId, item.material, item.amount,
                    item.x, item.y, item.vx, item.vy, item.angle, item.spin);
            iterator.set(replacement);
            used.add(replacementId);
            next = successor(replacementId, "world item");
            repaired++;
        }
        world.nextWorldItemId = Math.max(world.nextWorldItemId, available(used, next, "world item"));
        return repaired;
    }

    private static int repairShots(World world) {
        Set<Integer> used = new HashSet<>();
        int next = nextShotId(world);
        int repaired = 0;
        ListIterator<ProjectileShot> iterator = world.shots.listIterator();
        while (iterator.hasNext()) {
            ProjectileShot shot = iterator.next();
            if (shot == null) continue;
            if (shot.id >= 0 && used.add(shot.id)) continue;
            int replacementId = available(used, next, "projectile");
            ProjectileShot replacement = new ProjectileShot(replacementId, shot.ownerId, shot.weaponId,
                    shot.targetKey, shot.x, shot.y);
            replacement.lastX = shot.lastX;
            replacement.lastY = shot.lastY;
            iterator.set(replacement);
            used.add(replacementId);
            next = successor(replacementId, "projectile");
            repaired++;
        }
        setNextShotId(world, Math.max(currentNextShotId(world), available(used, next, "projectile")));
        return repaired;
    }

    private static int nextItemId(World world) {
        int next = 1;
        for (WorldItem item : world.items) {
            if (item == null || item.id < 0) continue;
            next = Math.max(next, successor(item.id, "world item"));
        }
        return next;
    }

    private static int nextShotId(World world) {
        int next = 1;
        for (ProjectileShot shot : world.shots) {
            if (shot == null || shot.id < 0) continue;
            next = Math.max(next, successor(shot.id, "projectile"));
        }
        return next;
    }

    private static int available(Set<Integer> used, int candidate, String kind) {
        int next = Math.max(1, candidate);
        while (used.contains(next)) next = successor(next, kind);
        return next;
    }

    private static int successor(int value, String kind) {
        if (value == Integer.MAX_VALUE) throw new IllegalStateException(kind + " ID space is exhausted.");
        return value + 1;
    }

    private static int currentNextShotId(World world) {
        try {
            return NEXT_SHOT_ID.getInt(world);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("Could not read the projectile ID allocator.", ex);
        }
    }

    private static void setNextShotId(World world, int value) {
        try {
            NEXT_SHOT_ID.setInt(world, value);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("Could not restore the projectile ID allocator.", ex);
        }
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    record RepairStats(int items, int projectiles) { }
}
