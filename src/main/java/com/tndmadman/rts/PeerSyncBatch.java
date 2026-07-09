package com.tndmadman.rts;

final class PeerSyncBatch {
    private PeerSyncBatch() { }

    static long send(World world, ClientViewCache views, ServerPeer[] peers, long sequence, NetOutbound out) {
        long next = sequence;
        for (ServerPeer peer : peers) next = PeerSyncSender.sendOne(world, views, peer, next, SyncKind.REGULAR, out);
        return next;
    }

    static long sendInitial(World world, ClientViewCache views, ServerPeer peer, long sequence, NetOutbound out) {
        return PeerSyncSender.sendOne(world, views, peer, sequence, SyncKind.INITIAL, out);
    }
}
