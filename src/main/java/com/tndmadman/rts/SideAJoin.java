package com.tndmadman.rts;

import java.net.InetAddress;

final class SideAJoin {
    private SideAJoin() { }

    static boolean handle(PeerServerSide server, String[] parts, ConnectionId connectionId, NetPacket packet) {
        switch (parts[0]) {
            case "JOIN" -> {
                InetAddress sourceAddress = packet == null ? null : packet.address();
                int sourcePort = packet == null ? 0 : packet.port();
                String name = parts.length > 1 ? parts[1] : "Player";
                String registrationVerifier = markerValue(parts, "AUTH_REGISTER");
                String proofNonce = markerValue(parts, "AUTH_PROOF_NONCE");
                String proof = markerValue(parts, "AUTH_PROOF");

                boolean registrationPath = proof.isBlank();
                InetAddress admissionAddress = registrationPath
                        ? RegistrationAddress.permitOnce(sourceAddress)
                        : sourceAddress;
                String source = sourceAddress == null ? "unknown" : sourceAddress.getHostAddress() + ':' + sourcePort;
                String phase = !registrationVerifier.isBlank() ? "registration response"
                        : !proof.isBlank() ? "authentication proof" : "initial join";
                System.out.println("[CONNECTION][SERVER][AUTH] JOIN " + phase + " name="
                        + Config.clean(name) + " source=" + source + " connection=" + connectionId + '.');

                try {
                    server.join(connectionId, admissionAddress, sourcePort, name,
                            registrationVerifier, proofNonce, proof,
                            server.requestedDev(parts), server.requestedDevToken(parts));
                    System.out.println("[CONNECTION][SERVER][AUTH] JOIN " + phase
                            + " processed name=" + Config.clean(name) + " connection=" + connectionId + '.');
                } catch (RuntimeException ex) {
                    System.err.println("[CONNECTION][SERVER][AUTH][FAILURE] JOIN " + phase
                            + " failed name=" + Config.clean(name) + " source=" + source
                            + " connection=" + connectionId + " error=" + ex.getClass().getSimpleName()
                            + ": " + (ex.getMessage() == null ? "" : ex.getMessage()));
                    ex.printStackTrace(System.err);
                    throw ex;
                }
                return true;
            }
            case "RESUME" -> {
                String source = packet == null || packet.address() == null ? "unknown"
                        : packet.address().getHostAddress() + ':' + packet.port();
                System.out.println("[CONNECTION][SERVER][AUTH] RESUME player="
                        + (parts.length > 1 ? parts[1] : "") + " source=" + source
                        + " connection=" + connectionId + '.');
                server.resume(connectionId, packet.address(), packet.port(),
                        parts.length > 1 ? parts[1] : "",
                        parts.length > 2 ? parts[2] : "",
                        markerValue(parts, "SESSION_PROOF_NONCE"),
                        markerValue(parts, "SESSION_PROOF"),
                        server.requestedResumeDev(parts), server.requestedResumeDevToken(parts));
                return true;
            }
            case "PING" -> { server.touch(connectionId); return true; }
            case "LEAVE" -> {
                System.out.println("[CONNECTION][SERVER] LEAVE connection=" + connectionId + '.');
                server.removePeer(connectionId);
                return true;
            }
            default -> { return false; }
        }
    }

    private static String markerValue(String[] parts, String marker) {
        if (parts == null || marker == null) return "";
        for (int i = 0; i < parts.length - 1; i++) if (marker.equalsIgnoreCase(parts[i])) return parts[i + 1];
        return "";
    }
}
