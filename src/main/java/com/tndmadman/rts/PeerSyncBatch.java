package com.tndmadman.rts;

final class PeerSyncBatch {
    private PeerSyncBatch() { }

    static long send(World world, ClientViewCache views, ServerPeer[] peers, long sequence, NetOutbound out) {
        long next = sequence;
        for (ServerPeer peer : peers) next = PeerSyncSender.sendOne(world, views, peer, next, SyncKind.REGULAR, out);
        return next;
    }
}
