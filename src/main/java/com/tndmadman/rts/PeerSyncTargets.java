package com.tndmadman.rts;

import java.util.Collection;

final class PeerSyncTargets {
    private PeerSyncTargets() { }

    static ServerPeer[] array(Collection<ServerPeer> peers) {
        return peers.toArray(new ServerPeer[0]);
    }
}
