package com.tndmadman.rts;

final class SyncPacketBuilder {
    private SyncPacketBuilder() { }

    static String build(World world, ClientViewCache views, String playerId, long sequence, SyncKind kind) {
        return build(world, views, playerId, sequence, kind, false);
    }

    static String build(World world, ClientViewCache views, String playerId, long sequence, SyncKind kind,
                        boolean fullResources) {
        if (kind == SyncKind.INITIAL || fullResources) ResourceSyncMode.fullForNextSnapshot();
        Snapshot snapshot = views.makeSnapshot(world, playerId, sequence);
        ResourceNetDebug.sendSnapshot(kind.name(), playerId, snapshot, world);
        if (kind == SyncKind.INITIAL) return SyncFrame.writeView(snapshot, views.viewRevision(playerId));
        if (fullResources) return SyncFrame.writeResourceCorrection(snapshot);
        return SnapshotWriter.write(snapshot);
    }
}
