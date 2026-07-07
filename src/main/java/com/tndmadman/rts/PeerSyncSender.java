package com.tndmadman.rts;

final class PeerSyncSender {
    private PeerSyncSender() { }

    static long sendOne(World world, ClientViewCache views, ServerPeer peer, long sequence, SyncKind kind, NetOutbound out) {
        if (peer == null) return sequence;
        String message = SyncPacketBuilder.build(world, views, peer.playerId(), sequence, kind);
        out.send(message, peer.address(), peer.port());
        return sequence + 1;
    }
}
