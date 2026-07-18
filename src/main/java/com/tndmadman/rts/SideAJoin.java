package com.tndmadman.rts;

final class SideAJoin {
    private SideAJoin() { }

    static boolean handle(PeerServerSide server, String[] parts, ConnectionId connectionId, NetPacket packet) {
        switch (parts[0]) {
            case "JOIN" -> {
                server.join(connectionId, packet.address(), packet.port(), parts.length > 1 ? parts[1] : "Player",
                        markerValue(parts, "AUTH_REGISTER"),
                        markerValue(parts, "AUTH_PROOF_NONCE"),
                        markerValue(parts, "AUTH_PROOF"),
                        server.requestedDev(parts), server.requestedDevToken(parts));
                return true;
            }
            case "RESUME" -> {
                server.resume(connectionId, packet.address(), packet.port(),
                        parts.length > 1 ? parts[1] : "",
                        parts.length > 2 ? parts[2] : "",
                        server.requestedResumeDev(parts), server.requestedResumeDevToken(parts));
                return true;
            }
            case "PING" -> { server.touch(connectionId); return true; }
            case "LEAVE" -> { server.removePeer(connectionId); return true; }
            default -> { return false; }
        }
    }

    private static String markerValue(String[] parts, String marker) {
        if (parts == null || marker == null) return "";
        for (int i = 0; i < parts.length - 1; i++) if (marker.equalsIgnoreCase(parts[i])) return parts[i + 1];
        return "";
    }
}
