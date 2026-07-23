package com.tndmadman.rts;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.Map;
import java.util.Set;

/** Narrow compatibility bridge for private session-retention and deleted-system notifications. */
final class PeerServerAdminBridge {
    private static final Field SESSIONS = field(PeerServerSide.class, "sessions");
    private static final Field PEERS = field(PeerServerSide.class, "peers");
    private static final Field DEV_REQUESTS = field(PeerServerSide.class, "devRequests");
    private static final Method SEND_DELETED = method(PeerServerSide.class, "sendDeletedSystems", Set.class);

    private PeerServerAdminBridge() { }

    static void retain(PeerServerSide server, String playerId) {
        Object session = session(server, playerId);
        setLong(session, "disconnectedAt", 0L);
    }

    static void release(PeerServerSide server, String playerId) {
        Object session = session(server, playerId);
        if (session == null || getBoolean(session, "connected")) return;
        setLong(session, "disconnectedAt", System.currentTimeMillis());
    }

    static InetAddress address(PeerServerSide server, String playerId) {
        if (server == null || playerId == null) return null;
        try {
            Object value = PEERS.get(server);
            if (!(value instanceof Map<?,?> peers)) return null;
            for (Object peerValue : peers.values()) {
                if (peerValue instanceof ServerPeer peer && playerId.equals(peer.playerId())) return peer.address();
            }
        } catch (ReflectiveOperationException ignored) { }
        return null;
    }

    static void setDevAccess(PeerServerSide server, String playerId, boolean enabled) {
        if (server == null || playerId == null || playerId.isBlank()) return;
        try {
            Object value = PEERS.get(server);
            if (value instanceof Map<?,?> raw) {
                @SuppressWarnings("unchecked") Map<ConnectionId,ServerPeer> peers = (Map<ConnectionId,ServerPeer>)raw;
                for (Map.Entry<ConnectionId,ServerPeer> entry : peers.entrySet()) {
                    ServerPeer peer = entry.getValue();
                    if (peer == null || !playerId.equals(peer.playerId())) continue;
                    entry.setValue(new ServerPeer(peer.playerId(), peer.connectionId(), peer.address(), peer.port(), peer.lastSeen(), enabled));
                    server.transport.sendOrdered("DEVSTATUS|" + (enabled ? "1" : "0"), peer.connectionId());
                }
            }
            Object requestsValue = DEV_REQUESTS.get(server);
            if (requestsValue instanceof Set<?> rawRequests) {
                @SuppressWarnings("unchecked") Set<String> requests = (Set<String>)rawRequests;
                DevAccessRequestState.resolve(requests, playerId);
            }
            Object session = session(server, playerId);
            setBoolean(session, "devFreeBuild", false);
            server.world.setDevFreeBuild(playerId, false);
            server.broadcastNow();
        } catch (ReflectiveOperationException ex) {
            System.err.println("Could not change runtime developer access: " + ex.getMessage());
        }
    }

    static int revokeAllDev(PeerServerSide server) {
        if (server == null) return 0;
        int count = 0;
        try {
            Object value = PEERS.get(server);
            if (value instanceof Map<?,?> raw) {
                @SuppressWarnings("unchecked") Map<ConnectionId,ServerPeer> peers = (Map<ConnectionId,ServerPeer>)raw;
                for (Map.Entry<ConnectionId,ServerPeer> entry : peers.entrySet()) {
                    ServerPeer peer = entry.getValue();
                    if (peer == null || !peer.devFreeBuild()) continue;
                    count++;
                    entry.setValue(new ServerPeer(peer.playerId(), peer.connectionId(), peer.address(), peer.port(), peer.lastSeen(), false));
                    server.transport.sendOrdered("DEVSTATUS|0", peer.connectionId());
                    Object session = session(server, peer.playerId());
                    setBoolean(session, "devFreeBuild", false);
                    server.world.setDevFreeBuild(peer.playerId(), false);
                }
            }
            server.broadcastNow();
        } catch (ReflectiveOperationException ex) {
            System.err.println("Could not revoke runtime developer access: " + ex.getMessage());
        }
        return count;
    }

    static void sendDeleted(PeerServerSide server, Set<String> systems) {
        if (server == null || systems == null || systems.isEmpty()) return;
        try { SEND_DELETED.invoke(server, systems); }
        catch (ReflectiveOperationException ex) { System.err.println("Could not send deleted-system notice: " + ex.getMessage()); }
    }

    static DeleteResult delete(PeerServerSide server, String playerId) {
        if (server == null || playerId == null || playerId.isBlank()) {
            return new DeleteResult(false, Set.of(), "Player identity is required.");
        }
        try {
            Object session = session(server, playerId);
            if (session == null) return new DeleteResult(false, Set.of(), "Unknown player identity: " + playerId);
            if (getBoolean(session, "connected")) {
                return new DeleteResult(false, Set.of(), playerId + " is connected; disconnect it before deletion.");
            }
            Object sessionsValue = SESSIONS.get(server);
            if (!(sessionsValue instanceof Map<?,?> rawSessions)) {
                return new DeleteResult(false, Set.of(), "Server session state is unavailable.");
            }
            @SuppressWarnings("unchecked") Map<String,Object> sessions = (Map<String,Object>)rawSessions;
            sessions.remove(playerId);
            Object requestsValue = DEV_REQUESTS.get(server);
            if (requestsValue instanceof Set<?> rawRequests) {
                @SuppressWarnings("unchecked") Set<String> requests = (Set<String>)rawRequests;
                requests.remove(playerId);
            }
            PlayerRegistry.activate(server.world);
            PlayerRegistry.remove(playerId);
            server.world.setDevFreeBuild(playerId, false);
            server.views.remove(playerId);
            Set<String> deletedSystems = server.world.removePlayerAndPruneEmptySystems(playerId);
            server.views.removeSystems(deletedSystems);
            server.broadcastNow();
            return new DeleteResult(true, Set.copyOf(deletedSystems), "Deleted retained identity " + playerId + ".");
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return new DeleteResult(false, Set.of(), "Could not delete retained identity: " + ex.getMessage());
        }
    }

    record DeleteResult(boolean success, Set<String> deletedSystems, String message) {
        DeleteResult {
            deletedSystems = deletedSystems == null ? Set.of() : Set.copyOf(deletedSystems);
            message = message == null ? "" : message;
        }
    }

    private static Object session(PeerServerSide server, String playerId) {
        if (server == null || playerId == null || playerId.isBlank()) return null;
        try {
            Object value = SESSIONS.get(server);
            return value instanceof Map<?,?> sessions ? sessions.get(playerId) : null;
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private static Field field(Class<?> type, String name) {
        try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException ex) { throw new ExceptionInInitializerError(ex); }
    }

    private static Method method(Class<?> type, String name, Class<?>... args) {
        try { Method method = type.getDeclaredMethod(name, args); method.setAccessible(true); return method; }
        catch (ReflectiveOperationException ex) { throw new ExceptionInInitializerError(ex); }
    }

    private static void setLong(Object target, String name, long value) {
        if (target == null) return;
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.setLong(target, value); }
        catch (ReflectiveOperationException ignored) { }
    }

    private static void setBoolean(Object target, String name, boolean value) {
        if (target == null) return;
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.setBoolean(target, value); }
        catch (ReflectiveOperationException ignored) { }
    }

    private static boolean getBoolean(Object target, String name) {
        if (target == null) return false;
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.getBoolean(target); }
        catch (ReflectiveOperationException ex) { return false; }
    }
}
