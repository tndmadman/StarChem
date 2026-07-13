package com.tndmadman.rts;

final class PeerSyncBatch {
    private PeerSyncBatch() { }

    static long send(World world, ClientViewCache views, ServerPeer[] peers, long sequence, NetOutbound out) {
        return send(world, views, peers, sequence, false, out);
    }

    static long send(World world, ClientViewCache views, ServerPeer[] peers, long sequence,
                     boolean fullResources, NetOutbound out) {
        long next = sequence;
        for (ServerPeer peer : peers) {
            next = PeerSyncSender.sendOne(world, views, peer, next, SyncKind.REGULAR, fullResources, out);
            sendNotices(world, peer, out);
        }
        return next;
    }

    static long sendInitial(World world, ClientViewCache views, ServerPeer peer, long sequence, NetOutbound out) {
        long next = PeerSyncSender.sendOne(world, views, peer, sequence, SyncKind.INITIAL, out);
        sendNotices(world, peer, out);
        return next;
    }

    private static void sendNotices(World world, ServerPeer peer, NetOutbound out) {
        if (world == null || peer == null || out == null) return;
        for (GameNotice notice : GameNoticeCenter.drain(world, peer.playerId())) {
            out.send(notice.packet(), peer.connectionId(), DeliveryClass.ORDERED);
        }
    }
}
