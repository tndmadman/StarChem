package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class AuthoritativeIdRepairValidator {
    private AuthoritativeIdRepairValidator() { }

    public static void main(String[] args) {
        validatesLootAllocatorRebuild();
        validatesDuplicateStateRepair();
        System.out.println("Authoritative ID repair validation passed.");
    }

    private static void validatesLootAllocatorRebuild() {
        World world = new World("ItemAllocatorValidator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        world.items.add(new WorldItem(13, Material.IRON, 10, 100, 100, 0, 0, 0, 0));
        world.nextWorldItemId = 1;

        EnumMap<Material,Double> cargo = new EnumMap<>(Material.class);
        cargo.put(Material.IRON, 20.0);
        int spawned = WorldLootDrops.scatter(world, cargo, 100, 100, 1, 73);

        require(spawned > 0, "Loot scatter did not create a world item.");
        require(uniqueItemIds(world), "Loot scatter reused a restored world-item ID.");
        require(world.nextWorldItemId > 14, "Loot allocator did not advance beyond restored IDs.");
        SnapshotWriter.write(snapshot(world));
    }

    private static void validatesDuplicateStateRepair() {
        World world = new World("DuplicateStateValidator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        world.items.add(new WorldItem(13, Material.IRON, 10, 100, 100, 0, 0, 0, 0));
        world.items.add(new WorldItem(13, Material.COPPER, 12, 120, 100, 0, 0, 0, 0));
        world.nextWorldItemId = 1;

        String weaponId = WeaponRules.WEAPONS.keySet().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No projectile weapon is configured."));
        world.shots.add(new ProjectileShot(7, "P1", weaponId, "U:P2:1", 100, 100));
        world.shots.add(new ProjectileShot(7, "P1", weaponId, "U:P2:1", 120, 100));

        expectReject(() -> SnapshotWriter.write(snapshot(world)), "duplicate value 7");
        AuthoritativeIdRepair.RepairStats repaired = AuthoritativeIdRepair.repairActive(world);

        require(repaired.items() == 1, "Duplicate world-item ID was not repaired exactly once.");
        require(repaired.projectiles() == 1, "Duplicate projectile ID was not repaired exactly once.");
        require(uniqueItemIds(world), "World-item IDs remain duplicated after repair.");
        require(uniqueShotIds(world), "Projectile IDs remain duplicated after repair.");

        WorldItem nextItem = world.addWorldItem(Material.IRON, 5, 140, 100, 0, 0, 0, 0);
        ProjectileShot nextShot = world.addShot("P1", weaponId, "U:P2:1", 140, 100);
        require(nextItem != null && uniqueItemIds(world), "Repaired world-item allocator immediately collided again.");
        require(nextShot != null && uniqueShotIds(world), "Repaired projectile allocator immediately collided again.");
        SnapshotWriter.write(snapshot(world));
    }

    private static Snapshot snapshot(World world) {
        List<ShotState> shots = new ArrayList<>();
        for (ProjectileShot shot : world.shots) {
            shots.add(new ShotState(shot.id, shot.ownerId, shot.weaponId, shot.targetKey,
                    shot.x, shot.y, shot.lastX, shot.lastY));
        }
        List<ItemState> items = new ArrayList<>();
        for (WorldItem item : world.items) {
            items.add(new ItemState(item.id, item.material.name(), item.amount, item.x, item.y,
                    item.vx, item.vy, item.angle, item.spin));
        }
        return new Snapshot(1, List.of(), List.of(), List.of(), List.of(), List.of(), shots, items, "", -1);
    }

    private static boolean uniqueItemIds(World world) {
        Set<Integer> ids = new HashSet<>();
        for (WorldItem item : world.items) if (item == null || !ids.add(item.id)) return false;
        return true;
    }

    private static boolean uniqueShotIds(World world) {
        Set<Integer> ids = new HashSet<>();
        for (ProjectileShot shot : world.shots) if (shot == null || !ids.add(shot.id)) return false;
        return true;
    }

    private static void expectReject(Runnable action, String expected) {
        try {
            action.run();
            throw new IllegalStateException("Expected malformed authoritative state to be rejected.");
        } catch (SnapshotDecodeException ex) {
            require(ex.getMessage() != null && ex.getMessage().contains(expected),
                    "Unexpected rejection: " + ex.getMessage());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
