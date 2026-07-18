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

    static void sendDeleted(PeerServerSide server, Set<String> systems) {
        if (server == null || systems == null || systems.isEmpty()) return;
        try { SEND_DELETED.invoke(server, systems); }
        catch (ReflectiveOperationException ex) { System.err.println("Could not send deleted-system notice: " + ex.getMessage()); }
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

    private static boolean getBoolean(Object target, String name) {
        if (target == null) return false;
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.getBoolean(target); }
        catch (ReflectiveOperationException ex) { return false; }
    }
}
