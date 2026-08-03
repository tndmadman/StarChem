package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Authenticated player-fit registration, fitting, publishing, and catalog synchronization. */
final class FitCommand {
    private FitCommand() { }

    /** Packet shape: FIT|requestId|action|base64-json. Actor identity is derived from the connection. */
    static void handle(PeerServerSide server, String[] parts, ConnectionId connectionId) {
        if (server == null || connectionId == null || !connectionId.valid()) return;
        server.touch(connectionId);
        String actorId = server.ownerId(connectionId, "");
        if (actorId.isBlank() || !server.owns(connectionId, actorId) || parts == null || parts.length != 4) {
            server.transport.recordMalformedPacket();
            return;
        }
        String requestId = cleanRequestId(parts[1]);
        String action = cleanAction(parts[2]);
        Map<String,Object> payload;
        try { payload = FitStateWire.decode(parts[3]); }
        catch (RuntimeException ex) {
            server.transport.recordMalformedPacket();
            sendResult(server, connectionId, requestId, false, "Malformed fit request.");
            return;
        }
        Result[] result = { Result.fail("Unknown fit action.") };
        server.change(actorId, () -> result[0] = apply(server.world, actorId, playerName(server, actorId), action, payload));
        if (result[0].catalogChanged() || result[0].worldChanged()) {
            sendCatalogs(server);
            server.broadcastNow();
        } else if ("REFRESH".equals(action)) {
            sendCatalog(server, connectionId);
        }
        sendResult(server, connectionId, requestId, result[0].success(), result[0].message());
    }

    static Result applyLocal(World world, String actorId, String action, Map<String,Object> payload) {
        return apply(world, actorId, PlayerRegistry.baseName(actorId), cleanAction(action), payload == null ? Map.of() : payload);
    }

    static Result applyHost(PeerServerSide server, String actorId, String action, Map<String,Object> payload) {
        if (server == null) return Result.fail("Authoritative server is unavailable.");
        Result result = apply(server.world, actorId, PlayerRegistry.baseName(actorId), cleanAction(action),
                payload == null ? Map.of() : payload);
        if (result.catalogChanged() || result.worldChanged()) {
            sendCatalogs(server);
            server.broadcastNow();
        }
        return result;
    }

    private static Result apply(World world, String actorId, String actorName, String action, Map<String,Object> payload) {
        if (world == null || actorId == null || actorId.isBlank()) return Result.fail("Fit request has no player identity.");
        try {
            return switch (action) {
                case "REFRESH" -> Result.ok("Fit catalog refreshed.", false, false);
                case "PUBLISH" -> publish(world, actorId, actorName, payload);
                case "UNPUBLISH" -> unpublish(world, actorId, payload);
                case "REFIT" -> refit(world, actorId, payload);
                case "REFIT_CLASS" -> refitClass(world, actorId, payload);
                case "BUILD" -> build(world, actorId, payload);
                default -> Result.fail("Unknown fit action.");
            };
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return Result.fail(ex.getMessage() == null ? "Fit request was rejected." : ex.getMessage());
        }
    }

    private static Result publish(World world, String actorId, String actorName, Map<String,Object> payload) {
        String name = PlayerFitRules.cleanName(ServerSaveStore.string(payload, "name", ""));
        ShipFitSpec spec = ShipFitSpec.from(payload.get("spec"));
        PublishedFit fit = WorldFitCatalog.publish(world, actorId, actorName, name, spec);
        return Result.ok("Published " + fit.name() + " to this server.", true, false);
    }

    private static Result unpublish(World world, String actorId, Map<String,Object> payload) {
        String id = ServerSaveStore.string(payload, "publishedId", "");
        if (!WorldFitCatalog.unpublish(world, actorId, id)) return Result.fail("Published fit was not found or is owned by another commander.");
        return Result.ok("Published fit removed from the server catalog.", true, false);
    }

    private static Result refit(World world, String actorId, Map<String,Object> payload) {
        Base preferred = ownedBase(world, actorId, ServerSaveStore.string(payload, "baseId", ""));
        String unitKey = ServerSaveStore.string(payload, "unitKey", "");
        Unit unit = world.units.get(unitKey);
        if (unit == null || !actorId.equals(unit.playerId)) return Result.fail("Selected ship was not found.");
        ShipLoadoutDefinition loadout = register(world, payload);
        RefitQueuePlanner.Result queued = RefitQueuePlanner.enqueue(world, actorId, List.of(unit), loadout,
                world.devFreeBuildFor(actorId), preferred);
        return queued.success() ? Result.ok(queued.message(), true, true) : Result.fail(queued.message());
    }

    private static Result refitClass(World world, String actorId, Map<String,Object> payload) {
        Base preferred = ownedBase(world, actorId, ServerSaveStore.string(payload, "baseId", ""));
        ShipLoadoutDefinition loadout = register(world, payload);
        List<Unit> eligible = new ArrayList<>();
        int already = 0, reserved = 0;
        for (Unit unit : world.units.values()) {
            if (!actorId.equals(unit.playerId) || unit.hp <= 0 || !loadout.hullId().equals(unit.shipTypeId)) continue;
            if (loadout.id().equals(unit.loadoutId)) { already++; continue; }
            if (ProductionSystem.refitReserved(world, unit.key())) { reserved++; continue; }
            eligible.add(unit);
        }
        if (eligible.isEmpty()) return Result.fail("No available " + Rules.ship(loadout.hullId()).name
                + " ships can be recalled. Already fitted: " + already + "; already reserved: " + reserved + ".");

        RefitQueuePlanner.Result queued = RefitQueuePlanner.enqueue(world, actorId, eligible, loadout,
                world.devFreeBuildFor(actorId), preferred);
        if (!queued.success()) return Result.fail(queued.message());
        world.status = queued.message() + " Already fitted: " + already + "; already reserved: " + reserved + ".";
        return Result.ok(world.status, true, true);
    }

    private static Result build(World world, String actorId, Map<String,Object> payload) {
        Base base = ownedBase(world, actorId, ServerSaveStore.string(payload, "baseId", ""));
        ShipLoadoutDefinition loadout = register(world, payload);
        boolean success = world.buildShip(base.id, loadout.id());
        return success ? Result.ok(world.status, true, true) : Result.fail(world.status);
    }

    private static ShipLoadoutDefinition register(World world, Map<String,Object> payload) {
        String name = PlayerFitRules.cleanName(ServerSaveStore.string(payload, "name", "Custom Fit"));
        return WorldFitCatalog.registerRuntime(world, name, ShipFitSpec.from(payload.get("spec")));
    }

    private static Base ownedBase(World world, String actorId, String baseId) {
        Base base = world.bases.get(baseId);
        if (base == null || !actorId.equals(base.playerId)) throw new IllegalArgumentException("Owned station was not found.");
        return base;
    }

    static void sendCatalog(PeerServerSide server, ConnectionId connectionId) {
        if (server == null || connectionId == null || !connectionId.valid()) return;
        server.transport.sendOrdered("FIT_CATALOG|" + FitStateWire.encode(WorldFitCatalog.networkView(server.world)), connectionId);
    }

    private static void sendCatalogs(PeerServerSide server) {
        for (PersistentPlayerSession session : server.persistentSessions()) {
            if (session == null || session.playerId().isBlank()) continue;
            ConnectionId connectionId = server.connectionIdForPlayer(session.playerId());
            if (connectionId.valid()) sendCatalog(server, connectionId);
        }
    }

    private static String playerName(PeerServerSide server, String playerId) {
        for (PersistentPlayerSession session : server.persistentSessions()) {
            if (session != null && playerId.equals(session.playerId())) return session.name();
        }
        return PlayerRegistry.baseName(playerId);
    }

    private static void sendResult(PeerServerSide server, ConnectionId connectionId, String requestId,
                                   boolean success, String message) {
        server.transport.sendOrdered("FIT_RESULT|" + cleanRequestId(requestId) + '|'
                + (success ? "OK" : "REJECTED") + '|' + encodeText(message), connectionId);
    }

    private static String cleanRequestId(String value) {
        if (value == null) return "0";
        String clean = value.replaceAll("[^A-Za-z0-9_.-]", "");
        return clean.isBlank() ? "0" : clean.substring(0, Math.min(64, clean.length()));
    }

    private static String cleanAction(String value) {
        if (value == null) return "";
        String clean = value.replaceAll("[^A-Za-z0-9_]", "").toUpperCase(java.util.Locale.ROOT);
        return clean.substring(0, Math.min(48, clean.length()));
    }

    private static String encodeText(String value) {
        String clean = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.length() > 512) clean = clean.substring(0, 512);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(clean.getBytes(StandardCharsets.UTF_8));
    }

    record Result(boolean success, boolean catalogChanged, boolean worldChanged, String message) {
        static Result ok(String message, boolean catalogChanged, boolean worldChanged) {
            return new Result(true, catalogChanged, worldChanged, message == null ? "Fit request completed." : message);
        }
        static Result fail(String message) { return new Result(false, false, false, message == null ? "Fit request rejected." : message); }
    }
}
