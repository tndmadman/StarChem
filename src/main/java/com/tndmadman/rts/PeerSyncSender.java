package com.tndmadman.rts;

final class PeerSyncSender {
    private PeerSyncSender() { }

    static long sendOne(World world, ClientViewCache views, ServerPeer peer, long sequence, SyncKind kind, NetOutbound out) {
        return sendOne(world, views, peer, sequence, kind, false, out);
    }

    static long sendOne(World world, ClientViewCache views, ServerPeer peer, long sequence, SyncKind kind,
                        boolean fullResources, NetOutbound out) {
        if (peer == null) return sequence;
        String message = SyncPacketBuilder.build(world, views, peer.playerId(), sequence, kind, fullResources);
        DeliveryClass delivery = kind == SyncKind.INITIAL
                ? DeliveryClass.VIEW_SNAPSHOT
                : fullResources ? DeliveryClass.FULL_CORRECTION : DeliveryClass.REGULAR_SNAPSHOT;
        out.send(message, peer.connectionId(), delivery);
        return sequence + 1;
    }
}
