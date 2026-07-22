package com.tndmadman.rts;

import java.util.Set;

/** Keeps client-originated developer requests separate from authorization decisions. */
final class DevAccessRequestState {
    private DevAccessRequestState() { }

    static boolean pending(boolean requested, boolean authorized) {
        return requested && !authorized;
    }

    static void resolve(Set<String> requests, String playerId) {
        if (requests == null || playerId == null || playerId.isBlank()) return;
        requests.remove(playerId);
    }
}
