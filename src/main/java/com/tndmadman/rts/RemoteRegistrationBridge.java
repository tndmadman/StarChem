package com.tndmadman.rts;

import java.net.InetAddress;

/**
 * Authorizes unused remote commander names to enter the existing registration
 * challenge while preserving the real socket source address for security policy.
 */
final class RemoteRegistrationBridge {
    private RemoteRegistrationBridge() { }

    static boolean allowed(PeerServerSide server, String name, InetAddress realAddress) {
        return server != null
                && realAddress != null
                && !realAddress.isLoopbackAddress()
                && !server.retainedAccountExists(name);
    }
}
