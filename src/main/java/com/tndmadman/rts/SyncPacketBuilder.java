package com.tndmadman.rts;

final class SyncPacketBuilder {
    private SyncPacketBuilder() { }

    static String build(World world, ClientViewCache views, String playerId, long sequence, SyncKind kind) {
        return build(world, views, playerId, sequence, kind, false);
    }

    static String build(World world, ClientViewCache views, String playerId, long sequence, SyncKind kind,
                        boolean fullResources) {
        prepareResources(kind, fullResources);
        try {
            return buildOnce(world, views, playerId, sequence, kind, fullResources);
        } catch (SnapshotDecodeException failure) {
            if (!AuthoritativeIdRepair.canRepair(failure)) throw failure;
            views.applyChange(world, playerId, () -> AuthoritativeIdRepair.repairActive(world));
            prepareResources(kind, fullResources);
            return buildOnce(world, views, playerId, sequence, kind, fullResources);
        }
    }

    private static String buildOnce(World world, ClientViewCache views, String playerId, long sequence,
                                    SyncKind kind, boolean fullResources) {
        boolean replaceResources = kind == SyncKind.INITIAL || fullResources;
        return ResourceSync.withPlayerContext(playerId, replaceResources, () -> {
            Snapshot snapshot = views.makeSnapshot(world, playerId, sequence);
            ServerFogOfWarState.observeSystem(world, playerId, snapshot.systemId());
            ResourceNetDebug.sendSnapshot(kind.name(), playerId, snapshot, world);
            if (kind == SyncKind.INITIAL) return SyncFrame.writeView(snapshot, views.viewRevision(playerId));
            if (fullResources) return SyncFrame.writeResourceCorrection(snapshot);
            return SnapshotWriter.write(snapshot);
        });
    }

    private static void prepareResources(SyncKind kind, boolean fullResources) {
        if (kind == SyncKind.INITIAL || fullResources) ResourceSyncMode.fullForNextSnapshot();
    }
}
