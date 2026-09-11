package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bounded, owner-scoped wire protocol for persistent fleet state and commands. */
final class FleetWire {
    private static final int MAX_COMMAND_CHARS = 16 * 1024;
    private static final int MAX_STATE_ENCODED_CHARS = 2_800_000;
    private static final int MAX_ARG_ENCODED_CHARS = 4_096;
    private static final int MAX_ARG_CHARS = 2_048;
    private static final int MAX_FIELDS = 16;
    private static final MiniJson.Limits STATE_LIMITS = new MiniJson.Limits(
            2_000_000, 48, 250_000, 2_048, 100_000, 64, true);

    private FleetWire() { }

    static String command(String ownerId, String action, Object... values) {
        String owner = encode(clean(ownerId, 96));
        String op = clean(action, 32).toUpperCase(Locale.ROOT);
        StringBuilder out = new StringBuilder("FLEET|").append(owner).append('|').append(op);
        if (values != null) {
            for (Object value : values) out.append('|').append(encode(clean(String.valueOf(value), MAX_ARG_CHARS)));
        }
        if (out.length() > MAX_COMMAND_CHARS) throw new IllegalArgumentException("Fleet command exceeds wire limit.");
        return out.toString();
    }

    static boolean handleServer(PeerServerSide server, String raw, NetPacket packet) {
        if (raw == null || !raw.startsWith("FLEET|")) return false;
        if (server == null || packet == null || packet.connectionId() == null || !packet.connectionId().valid()) return true;
        ConnectionId connectionId = packet.connectionId();
        if (raw.length() > MAX_COMMAND_CHARS) {
            server.transport.recordMalformedPacket();
            sendResult(server, connectionId, "INVALID", "Fleet request exceeds the command size limit.");
            return true;
        }
        try {
            Parsed parsed = parse(raw);
            String actorId = server.ownerId(connectionId, "");
            if (actorId.isBlank() || !server.owns(connectionId, actorId)) {
                server.transport.recordMalformedPacket();
                return true;
            }
            if (ObserverSessions.isObserver(server.world, actorId)) {
                sendResult(server, connectionId, "FORBIDDEN", "Observer sessions are read-only.");
                return true;
            }
            if (!actorId.equals(parsed.ownerId())) {
                sendResult(server, connectionId, "FORBIDDEN", "Fleet owner does not match the authenticated session.");
                return true;
            }
            server.touch(connectionId);
            Outcome outcome = apply(server.world, actorId, parsed);
            sendResult(server, connectionId, outcome.status(), outcome.message());
            sendState(server, connectionId, actorId);
            if (outcome.mutated()) server.broadcastNow();
        } catch (RuntimeException ex) {
            server.transport.recordMalformedPacket();
            sendResult(server, connectionId, "INVALID", "Malformed fleet command.");
        }
        return true;
    }

    static Outcome applyLocal(World world, String actorId, String raw) {
        if (world == null) return new Outcome(false, "INVALID", "Fleet world is unavailable.");
        try {
            Parsed parsed = parse(raw);
            if (!clean(actorId, 96).equals(parsed.ownerId())) {
                return new Outcome(false, "FORBIDDEN", "Fleet owner does not match the local session.");
            }
            Outcome outcome = apply(world, actorId, parsed);
            world.status = outcome.message();
            return outcome;
        } catch (RuntimeException ex) {
            world.status = "Malformed fleet command.";
            return new Outcome(false, "INVALID", world.status);
        }
    }

    static String statePacket(World world, String ownerId) {
        String owner = clean(ownerId, 96);
        Map<String,Object> captured = FleetManager.capture(world);
        List<Object> fleets = new ArrayList<>();
        long nextFleetId = 1;
        long nextOrderId = 1;
        for (Object raw : ServerSaveStore.list(captured.get("fleets"))) {
            Map<String,Object> row = ServerSaveStore.object(raw);
            if (!owner.equals(ServerSaveStore.string(row, "ownerId", ""))) continue;
            fleets.add(new LinkedHashMap<>(row));
            nextFleetId = Math.max(nextFleetId, ServerSaveStore.longValue(row, "fleetId", 0) + 1);
            Map<String,Object> order = ServerSaveStore.object(row.get("order"));
            nextOrderId = Math.max(nextOrderId, ServerSaveStore.longValue(order, "orderId", 0) + 1);
        }
        Map<String,Object> state = new LinkedHashMap<>();
        state.put("ownerId", owner);
        state.put("nextFleetId", nextFleetId);
        state.put("nextOrderId", nextOrderId);
        state.put("fleets", fleets);
        String json = MiniJson.stringify(state);
        if (json.length() > STATE_LIMITS.maxDocumentChars()) throw new IllegalStateException("Owner fleet state exceeds bounded projection limit.");
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        if (encoded.length() > MAX_STATE_ENCODED_CHARS) throw new IllegalStateException("Owner fleet state exceeds wire limit.");
        return "FLEET_STATE|" + encoded;
    }

    static boolean acceptClientPacket(PeerClientSide client, String raw) {
        if (client == null || raw == null) return false;
        if (raw.startsWith("FLEET_STATE|")) {
            applyClientState(client, raw);
            return true;
        }
        if (raw.startsWith("FLEET_RESULT|")) {
            String[] parts = raw.split("\\|", 4);
            if (parts.length == 4) {
                String message = decode(parts[3], 2_048, 512);
                if (message.isBlank()) message = "The server processed the fleet request.";
                client.world.status = message;
                AlertCenter.push(client.world, message);
            }
            return true;
        }
        return false;
    }

    static Map<String,String> ownerLocations(World world, String ownerId) {
        if (world == null || ownerId == null || ownerId.isBlank()) return Map.of();
        OwnerFleetLocationRegistry.State projected = OwnerFleetLocationRegistry.state(world);
        if (projected.initialized() && ownerId.equals(projected.ownerId())) return projected.locations();
        return world.ownerUnitLocations(ownerId);
    }

    private static void applyClientState(PeerClientSide client, String raw) {
        if (raw.length() > MAX_STATE_ENCODED_CHARS + 32) throw new SnapshotDecodeException("Fleet state exceeds wire limit.");
        String encoded = raw.substring("FLEET_STATE|".length());
        String json = decode(encoded, MAX_STATE_ENCODED_CHARS, STATE_LIMITS.maxDocumentChars());
        if (json.isBlank()) throw new SnapshotDecodeException("Fleet state is empty or invalid.");
        Object parsed = MiniJson.parse(json, STATE_LIMITS);
        Map<String,Object> state = ServerSaveStore.object(parsed);
        String owner = clean(ServerSaveStore.string(state, "ownerId", ""), 96);
        String local = clean(client.localPlayerId(), 96);
        if (owner.isBlank() || !owner.equals(local)) throw new SnapshotDecodeException("Fleet state owner does not match the authenticated client.");
        for (Object rawFleet : ServerSaveStore.list(state.get("fleets"))) {
            Map<String,Object> row = ServerSaveStore.object(rawFleet);
            if (!owner.equals(clean(ServerSaveStore.string(row, "ownerId", ""), 96))) {
                throw new SnapshotDecodeException("Fleet state contains a foreign owner.");
            }
        }
        FleetManager.restore(client.world, state);
        StrategicSummaryService.invalidate(client.world);
    }

    private static void sendState(PeerServerSide server, ConnectionId connectionId, String ownerId) {
        try {
            server.transport.sendOrdered(statePacket(server.world, ownerId), connectionId);
        } catch (RuntimeException ex) {
            server.transport.recordMalformedPacket();
            sendResult(server, connectionId, "LIMIT", "Fleet state is too large to synchronize safely.");
        }
    }

    private static void sendResult(PeerServerSide server, ConnectionId connectionId, String status, String message) {
        String safeStatus = clean(status, 32).toUpperCase(Locale.ROOT);
        String packet = "FLEET_RESULT|SERVER|" + safeStatus + "|" + encode(clean(message, 512));
        server.transport.sendOrdered(packet, connectionId);
    }

    private static Outcome apply(World world, String actorId, Parsed parsed) {
        List<String> a = parsed.args();
        return switch (parsed.action()) {
            case "SYNC" -> new Outcome(false, "APPLIED", "Fleet state synchronized.");
            case "CREATE" -> {
                requireArgs(a, 2);
                FleetCreateResult result = FleetManager.create(world, actorId, a.get(0), members(a.get(1)));
                yield new Outcome(result.result() == FleetMutationResult.APPLIED, result.result().name(),
                        result.result() == FleetMutationResult.APPLIED
                                ? "Fleet created (#" + result.fleetId() + ")." : "Fleet creation rejected: " + result.result() + ".");
            }
            case "RENAME" -> mutation(FleetManager.rename(world, actorId, longValue(a, 0), longValue(a, 1), arg(a, 2)), "rename");
            case "DISBAND" -> mutation(FleetManager.disband(world, actorId, longValue(a, 0), longValue(a, 1)), "disband");
            case "ADD" -> mutation(FleetManager.addMembers(world, actorId, longValue(a, 0), longValue(a, 1), members(arg(a, 2))), "attach");
            case "DETACH" -> mutation(FleetManager.removeMembers(world, actorId, longValue(a, 0), longValue(a, 1), members(arg(a, 2))), "detach");
            case "FORMATION" -> mutation(FleetManager.setFormation(world, actorId, longValue(a, 0), longValue(a, 1),
                    enumValue(FleetFormation.class, arg(a, 2))), "formation");
            case "POLICY" -> commandResult(FleetAuthorityService.applyCombatPolicy(world, actorId,
                    longValue(a, 0), longValue(a, 1), enumValue(CombatStance.class, arg(a, 2)),
                    enumValue(TargetPriorityPolicy.class, arg(a, 3))));
            case "MOVE" -> commandResult(FleetAuthorityService.moveToSystem(world, actorId,
                    longValue(a, 0), longValue(a, 1), arg(a, 2)));
            case "RALLY" -> commandResult(FleetAuthorityService.rally(world, actorId,
                    longValue(a, 0), longValue(a, 1), arg(a, 2), doubleValue(a, 3), doubleValue(a, 4)));
            case "MERGE" -> mutation(FleetManager.merge(world, actorId, longValue(a, 0), longValue(a, 1),
                    longValue(a, 2), longValue(a, 3)), "merge");
            case "SPLIT" -> {
                requireArgs(a, 4);
                FleetCreateResult result = FleetManager.split(world, actorId, longValue(a, 0), longValue(a, 1),
                        a.get(2), members(a.get(3)));
                yield new Outcome(result.result() == FleetMutationResult.APPLIED, result.result().name(),
                        result.result() == FleetMutationResult.APPLIED
                                ? "Fleet split created (#" + result.fleetId() + ")." : "Fleet split rejected: " + result.result() + ".");
            }
            default -> new Outcome(false, "INVALID", "Unknown fleet command: " + parsed.action() + ".");
        };
    }

    private static Outcome mutation(FleetMutationResult result, String action) {
        FleetMutationResult safe = result == null ? FleetMutationResult.INVALID : result;
        return new Outcome(safe == FleetMutationResult.APPLIED, safe.name(),
                safe == FleetMutationResult.APPLIED ? "Fleet " + action + " applied." : "Fleet " + action + " rejected: " + safe + ".");
    }

    private static Outcome commandResult(FleetCommandResult result) {
        if (result == null) return new Outcome(false, "INVALID", "Fleet command failed.");
        return new Outcome(result.applied(), result.applied() ? "APPLIED" : "INVALID", result.message());
    }

    private static Parsed parse(String raw) {
        if (raw == null || raw.length() > MAX_COMMAND_CHARS) throw new IllegalArgumentException("Fleet command size");
        String[] parts = raw.split("\\|", MAX_FIELDS);
        if (parts.length < 3 || !"FLEET".equals(parts[0])) throw new IllegalArgumentException("Fleet command header");
        String owner = decode(parts[1], 512, 96);
        String action = clean(parts[2], 32).toUpperCase(Locale.ROOT);
        if (owner.isBlank() || action.isBlank()) throw new IllegalArgumentException("Fleet command identity");
        List<String> args = new ArrayList<>();
        for (int i = 3; i < parts.length; i++) args.add(decode(parts[i], MAX_ARG_ENCODED_CHARS, MAX_ARG_CHARS));
        return new Parsed(owner, action, List.copyOf(args));
    }

    private static Set<String> members(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return out;
        for (String item : text.split(",", FleetManager.MAX_MEMBERS_PER_FLEET + 1)) {
            String key = clean(item, 256);
            if (!key.isBlank()) out.add(key);
            if (out.size() > FleetManager.MAX_MEMBERS_PER_FLEET) throw new IllegalArgumentException("Too many fleet members");
        }
        return out;
    }

    private static String arg(List<String> args, int index) {
        requireArgs(args, index + 1);
        return args.get(index);
    }

    private static long longValue(List<String> args, int index) {
        try { return Long.parseLong(arg(args, index)); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid fleet number", ex); }
    }

    private static double doubleValue(List<String> args, int index) {
        try {
            double value = Double.parseDouble(arg(args, index));
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite fleet coordinate");
            return value;
        } catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid fleet coordinate", ex); }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try { return Enum.valueOf(type, clean(value, 64).toUpperCase(Locale.ROOT)); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("Invalid fleet enum", ex); }
    }

    private static void requireArgs(List<String> args, int count) {
        if (args == null || args.size() < count) throw new IllegalArgumentException("Missing fleet command argument");
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value, int maxEncoded, int maxDecoded) {
        if (value == null || value.length() > maxEncoded) throw new IllegalArgumentException("Encoded fleet field exceeds limit");
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            if (decoded.length() > maxDecoded) throw new IllegalArgumentException("Decoded fleet field exceeds limit");
            return decoded;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid fleet field encoding", ex);
        }
    }

    private static String clean(String value, int max) {
        String text = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').replace('|', ' ').trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    record Outcome(boolean mutated, String status, String message) { }
    private record Parsed(String ownerId, String action, List<String> args) { }
}
