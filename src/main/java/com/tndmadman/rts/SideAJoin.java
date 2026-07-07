package com.tndmadman.rts;

final class SideAJoin {
    private SideAJoin() { }

    static boolean handle(PeerServerSide s, String[] p, String ep, NetPacket packet) {
        switch (p[0]) {
            case "JOIN" -> { s.join(ep, packet.address(), packet.port(), p.length > 1 ? p[1] : "Player", s.requestedDev(p)); return true; }
            case "PING" -> { s.touch(ep); return true; }
            case "LEAVE" -> { s.removePeer(ep); return true; }
            default -> { return false; }
        }
    }
}
