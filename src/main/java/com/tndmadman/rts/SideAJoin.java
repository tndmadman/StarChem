package com.tndmadman.rts;

final class SideAJoin {
    private SideAJoin() { }

    static boolean handle(PeerServerSide server, String[] parts, String endpoint, NetPacket packet) {
        switch (parts[0]) {
            case "JOIN" -> {
                server.join(endpoint, packet.address(), packet.port(), parts.length > 1 ? parts[1] : "Player",
                        server.requestedDev(parts), server.requestedDevToken(parts));
                return true;
            }
            case "RESUME" -> {
                server.resume(endpoint, packet.address(), packet.port(),
                        parts.length > 1 ? parts[1] : "",
                        parts.length > 2 ? parts[2] : "",
                        server.requestedResumeDev(parts), server.requestedResumeDevToken(parts));
                return true;
            }
            case "PING" -> { server.touch(endpoint); return true; }
            case "LEAVE" -> { server.removePeer(endpoint); return true; }
            default -> { return false; }
        }
    }
}
