package com.tndmadman.rts;

final class SyncPacketBuilder {
    private SyncPacketBuilder() { }

    static String build(World world, ClientViewCache views, String playerId, long sequence, SyncKind kind) {
        if (kind == SyncKind.INITIAL) ResourceSyncMode.fullForNextSnapshot();
        Snapshot snapshot = views.makeSnapshot(world, playerId, sequence);
        return kind == SyncKind.INITIAL ? SyncFrame.write(snapshot) : SnapshotWriter.write(snapshot);
    }
}
