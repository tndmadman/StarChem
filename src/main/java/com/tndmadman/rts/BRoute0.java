package com.tndmadman.rts;

final class BRoute0 {
    private BRoute0() { }

    static boolean apply(PeerClientSide c, String message) {
        if (!SyncFrame.matches(message)) return false;
        c.readFullView(message);
        return true;
    }
}
