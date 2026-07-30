package com.tndmadman.rts;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.util.Locale;
import java.util.Map;

/**
 * Routes unused remote commander names through the server's existing registration
 * challenge without changing the server's retained-account authentication path.
 */
final class RemoteRegistrationBridge {
    private static final Field SESSIONS = field(PeerServerSide.class, "sessions");
    private static final Field PEERS = field(PeerServerSide.class, "peers");

    private RemoteRegistrationBridge() { }

    static JoinAddress select(PeerServerSide server, String name, InetAddress realAddress) {
        if (realAddress == null || realAddress.isLoopbackAddress() || accountExists(server, name)) {
            return new JoinAddress(realAddress, false);
        }
        return new JoinAddress(InetAddress.getLoopbackAddress(), true);
    }

    static void restoreRealAddress(PeerServerSide server, ConnectionId connectionId,
                                   InetAddress realAddress, int realPort) {
        if (server == null || connectionId == null || !connectionId.valid() || realAddress == null) return;
        try {
            Object value = PEERS.get(server);
            if (!(value instanceof Map<?,?> raw)) return;
            @SuppressWarnings("unchecked") Map<ConnectionId, ServerPeer> peers = (Map<ConnectionId, ServerPeer>) raw;
            ServerPeer peer = peers.get(connectionId);
            if (peer == null || realAddress.equals(peer.address()) && realPort == peer.port()) return;
            peers.put(connectionId, new ServerPeer(peer.playerId(), peer.connectionId(), realAddress,
                    realPort, peer.lastSeen(), peer.devFreeBuild()));
            System.out.println("[CONNECTION][SERVER][REGISTRATION] Restored remote endpoint player="
                    + peer.playerId() + " source=" + realAddress.getHostAddress() + ':' + realPort + '.');
        } catch (ReflectiveOperationException ex) {
            System.err.println("[CONNECTION][SERVER][REGISTRATION][FAILURE] Could not restore remote endpoint: "
                    + ex.getClass().getSimpleName() + ": " + safe(ex.getMessage()));
        }
    }

    private static boolean accountExists(PeerServerSide server, String name) {
        if (server == null) return false;
        String wanted = normalized(name);
        if (wanted.isBlank()) return false;
        try {
            Object value = SESSIONS.get(server);
            if (!(value instanceof Map<?,?> sessions)) return false;
            for (Object session : sessions.values()) {
                if (session == null) continue;
                Field nameField = session.getClass().getDeclaredField("name");
                nameField.setAccessible(true);
                Object current = nameField.get(session);
                if (wanted.equals(normalized(current == null ? "" : current.toString()))) return true;
            }
        } catch (ReflectiveOperationException ex) {
            System.err.println("[CONNECTION][SERVER][REGISTRATION][FAILURE] Could not inspect retained names: "
                    + ex.getClass().getSimpleName() + ": " + safe(ex.getMessage()));
            return true;
        }
        return false;
    }

    private static String normalized(String value) {
        return Config.clean(value).toLowerCase(Locale.ROOT);
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').replace('|', ' ').trim();
    }

    record JoinAddress(InetAddress address, boolean remoteRegistration) { }
}
