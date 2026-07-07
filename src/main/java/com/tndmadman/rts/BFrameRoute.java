package com.tndmadman.rts;

final class BFrameRoute {
    private BFrameRoute() { }

    static boolean handle(PeerClientSide c, String message) {
        if (!SyncFrame.matches(message)) return false;
        c.readFullView(message);
        return true;
    }
}
