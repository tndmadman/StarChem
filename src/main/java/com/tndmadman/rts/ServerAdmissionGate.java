package com.tndmadman.rts;

import java.net.InetAddress;

/** Applies identity-dependent admission policy only after JOIN authentication succeeds. */
@FunctionalInterface
interface ServerAdmissionGate {
    String denialReason(ConnectionId connectionId, String playerId, String playerName,
                        InetAddress address, boolean newIdentity, long now);

    static ServerAdmissionGate open() {
        return (connectionId, playerId, playerName, address, newIdentity, now) -> "";
    }
}
