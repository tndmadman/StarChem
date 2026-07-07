package com.tndmadman.rts;

final class ServerSync {
    private ServerSync() { }

    static long regular(World world, ClientViewCache views, ServerPeer[] peers, long sequence, NetOutbound out) {
        return PeerSyncBatch.send(world, views, peers, sequence, out);
    }

    static long initial(World world, ClientViewCache views, ServerPeer peer, long sequence, NetOutbound out) {
        return PeerSyncSender.sendOne(world, views, peer, sequence, SyncKind.INITIAL, out);
    }
}
