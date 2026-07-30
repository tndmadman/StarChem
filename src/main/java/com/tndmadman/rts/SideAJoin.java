package com.tndmadman.rts;

import java.net.InetAddress;

final class SideAJoin {
    private SideAJoin() { }

    static boolean handle(PeerServerSide server, String[] parts, ConnectionId connectionId, NetPacket packet) {
        switch (parts[0]) {
            case "JOIN" -> {
                String name = parts.length > 1 ? parts[1] : "Player";
                String registrationVerifier = markerValue(parts, "AUTH_REGISTER");
                String proofNonce = markerValue(parts, "AUTH_PROOF_NONCE");
                String proof = markerValue(parts, "AUTH_PROOF");
                InetAddress realAddress = packet == null ? null : packet.address();
                int realPort = packet == null ? 0 : packet.port();
                String source = realAddress == null ? "unknown" : realAddress.getHostAddress() + ':' + realPort;
                RemoteRegistrationBridge.JoinAddress joinAddress =
                        RemoteRegistrationBridge.select(server, name, realAddress);
                boolean remoteRegistration = joinAddress.remoteRegistration();
                String phase = !registrationVerifier.isBlank() ? "registration response"
                        : !proof.isBlank() ? "authentication proof"
                        : remoteRegistration ? "remote registration request" : "initial join";
                System.out.println("[CONNECTION][SERVER][AUTH] JOIN " + phase + " name="
                        + Config.clean(name) + " source=" + source + " connection=" + connectionId + '.');
                if (remoteRegistration) {
                    System.out.println("[CONNECTION][SERVER][REGISTRATION] Unused remote commander name="
                            + Config.clean(name) + " source=" + source
                            + "; issuing the existing registration challenge.");
                }
                try {
                    server.join(connectionId,
                            joinAddress.address(),
                            realPort,
                            name, registrationVerifier, proofNonce, proof,
                            remoteRegistration ? false : server.requestedDev(parts),
                            remoteRegistration ? "" : server.requestedDevToken(parts));
                    if (remoteRegistration) {
                        RemoteRegistrationBridge.restoreRealAddress(server, connectionId, realAddress, realPort);
                    }
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
