package com.tndmadman.rts;

import java.util.Set;

/** Validates per-client resource visibility, depletion tombstones, and retry-safe delta state. */
public final class ResourceReplicationValidator {
    private ResourceReplicationValidator() { }

    public static void main(String[] args) {
        World world = new World("Resource replication validator", Set.of(),
                StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("P1", "Miner one", 0x50BEFF);
        PlayerRegistry.register("P2", "Miner two", 0xFF5F55, false);

        world.units.clear();
        world.bases.clear();
        world.resources.clear();
        world.shots.clear();
        world.items.clear();

        Unit firstMiner = new Unit("P1", 1, "prospector", 4_000, 1_000);
        Unit secondMiner = new Unit("P2", 1, "prospector", 4_000, 1_300);
        world.units.put(firstMiner.key(), firstMiner);
        world.units.put(secondMiner.key(), secondMiner);

        ResourceNode node = new ResourceNode(77, "Validator iron", NodeKind.SILICATE_ROCK,
                Material.IRON, 1_000, 1_000, 100, 5, 8);
        world.resources.add(node);

        require(snapshot(world, "P1", 1, false).resources().isEmpty(),
                "A hidden resource leaked into the first player's sparse snapshot.");

        firstMiner.x = 1_100;
        boolean failed = false;
        try {
            ResourceSync.withPlayerContext("P1", false, () -> {
                WorldNetAccess.snapshot(world, 2);
                throw new IllegalStateException("intentional snapshot build failure");
            });
        } catch (IllegalStateException expected) {
            failed = true;
        }
        require(failed, "The retry-safety fixture did not throw.");

        Snapshot revealed = snapshot(world, "P1", 3, false);
        require(active(revealed, node.id),
                "A newly visible resource was not emitted without a mining target or dirty mark.");
        require(snapshot(world, "P1", 4, false).resources().isEmpty(),
                "An unchanged untargeted resource was resent as a regular delta.");
        require(snapshot(world, "P2", 5, false).resources().isEmpty(),
                "One player's resource visibility leaked into another player's replication cache.");

        node.deplete();
        Snapshot depleted = snapshot(world, "P1", 6, false);
        require(inactive(depleted, node.id),
                "A known depleted resource did not emit an inactive tombstone.");
        require(snapshot(world, "P1", 7, false).resources().isEmpty(),
                "A depletion tombstone was repeatedly emitted after client state converged.");

        node.active = true;
        node.amount = node.maxAmount;
        node.respawnTimer = 0;
        Snapshot respawned = snapshot(world, "P1", 8, false);
        require(active(respawned, node.id),
                "A visible respawned resource was not reintroduced to the client.");

        firstMiner.x = 4_000;
        Snapshot hidden = snapshot(world, "P1", 9, false);
        require(inactive(hidden, node.id),
                "A resource leaving sensor coverage did not emit a removal tombstone.");

        firstMiner.x = 1_100;
        Snapshot visibleAgain = snapshot(world, "P1", 10, false);
        require(active(visibleAgain, node.id),
                "A resource re-entering sensor coverage was not emitted.");

        firstMiner.startAutoHarvest(node.id);
        require(active(snapshot(world, "P1", 11, false), node.id),
                "A targeted resource was not repeated for authoritative orbit correction.");
        require(active(snapshot(world, "P1", 12, false), node.id),
                "A targeted resource stopped receiving repeated correction snapshots.");

        firstMiner.automationResourceId = -1;
        firstMiner.task = UnitTask.IDLE;
        Snapshot full = snapshot(world, "P1", 13, true);
        require(active(full, node.id),
                "A full resource replacement omitted a currently visible resource.");
        require(snapshot(world, "P1", 14, false).resources().isEmpty(),
                "A full replacement did not reset the player's resource delta baseline.");

        System.out.println("Per-client resource replication validation passed.");
    }

    private static Snapshot snapshot(World world, String playerId, long sequence, boolean fullResources) {
        return ResourceSync.withPlayerContext(playerId, fullResources,
                () -> FogSnapshotFilter.forPlayer(world, playerId,
                        WorldNetAccess.snapshot(world, sequence)));
    }

    private static boolean active(Snapshot snapshot, int resourceId) {
        ResourceState state = resource(snapshot, resourceId);
        return state != null && state.active() && state.amount() > 0;
    }

    private static boolean inactive(Snapshot snapshot, int resourceId) {
        ResourceState state = resource(snapshot, resourceId);
        return state != null && !state.active() && state.amount() == 0;
    }

    private static ResourceState resource(Snapshot snapshot, int resourceId) {
        for (ResourceState state : snapshot.resources()) {
            if (state.id() == resourceId) return state;
        }
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
