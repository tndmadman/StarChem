package com.tndmadman.rts;

final class BRoute0 {
    private BRoute0() { }

    static boolean apply(PeerClientSide c, String message) {
        if (message != null && message.startsWith("NOTICE|")) {
            return GameNoticeCenter.acceptRemote(c.world, message);
        }
        if (!SyncFrame.matches(message)) return false;
        c.readFullView(message);
        return true;
    }
}
