package com.tndmadman.rts;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** UI bridge into authenticated fit commands, with direct local-world execution. */
final class FitNetworkBridge {
    private static final Field CLIENT = field(PeerNetwork.class, "client");
    private static final Field SERVER = field(PeerNetwork.class, "server");
    private static final Method SEND_COMMAND = method(PeerClientSide.class, "sendCommandToServer", String.class);
    private static final AtomicLong REQUESTS = new AtomicLong();

    private FitNetworkBridge() { }

    static boolean submit(PeerNetwork network, World world, String action, String name, ShipFitSpec spec,
                          String baseId, String unitKey, String publishedId) {
        Map<String,Object> payload = new LinkedHashMap<>();
        if (name != null) payload.put("name", name);
        if (spec != null) payload.put("spec", spec.toMap());
        if (baseId != null) payload.put("baseId", baseId);
        if (unitKey != null) payload.put("unitKey", unitKey);
        if (publishedId != null) payload.put("publishedId", publishedId);
        return submit(network, world, action, payload);
    }

    static boolean refresh(PeerNetwork network, World world) { return submit(network, world, "REFRESH", Map.of()); }

    static boolean submit(PeerNetwork network, World world, String action, Map<String,Object> payload) {
        if (world == null || action == null || action.isBlank()) return false;
        if (network == null || !network.clientMode()) {
            FitCommand.Result result;
            if (network != null) {
                try {
                    Object server = SERVER.get(network);
                    result = server instanceof PeerServerSide peerServer
                            ? FitCommand.applyHost(peerServer, PlayerRegistry.localId(), action, payload)
                            : FitCommand.applyLocal(world, PlayerRegistry.localId(), action, payload);
                } catch (IllegalAccessException ex) {
                    result = FitCommand.Result.fail("Could not reach the authoritative fit service.");
                }
            } else {
                result = FitCommand.applyLocal(world, PlayerRegistry.localId(), action, payload);
            }
            world.status = result.message();
            AlertCenter.push(world, result.message());
            return result.success();
        }
        try {
            Object client = CLIENT.get(network);
            if (!(client instanceof PeerClientSide)) return false;
            String requestId = Long.toUnsignedString(REQUESTS.incrementAndGet(), 36);
            String packet = "FIT|" + requestId + '|' + cleanAction(action) + '|' + FitStateWire.encode(payload);
            SEND_COMMAND.invoke(client, packet);
            world.status = "Fit request submitted for server authorization.";
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            world.status = "Could not submit the fit request.";
            System.err.println(world.status + " " + ex.getClass().getSimpleName());
            return false;
        }
    }

    private static String cleanAction(String value) {
        String clean = value == null ? "" : value.replaceAll("[^A-Za-z0-9_]", "").toUpperCase(java.util.Locale.ROOT);
        return clean.substring(0, Math.min(48, clean.length()));
    }

    private static Field field(Class<?> type, String name) {
        try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException ex) { throw new ExceptionInInitializerError(ex); }
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) {
        try { Method method = type.getDeclaredMethod(name, parameters); method.setAccessible(true); return method; }
        catch (ReflectiveOperationException ex) { throw new ExceptionInInitializerError(ex); }
    }
}
