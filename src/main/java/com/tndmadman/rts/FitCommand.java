package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
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
        server.change(actorId, () -> result[0] = apply(server.world, actorId,
                playerName(server, actorId), action, payload));
        if (result[0].catalogChanged() || result[0].worldChanged()) {
            sendCatalogs(server);
            server.broadcastNow();
        } else if ("REFRESH".equals(action)) {
            sendCatalog(server, connectionId);
        }
        sendResult(server, connectionId, requestId,
                result[0].success(), result[0].message());
    }

    static Result applyLocal(World world, String actorId, String action,
                             Map<String,Object> payload) {
        return apply(world, actorId, PlayerRegistry.baseName(actorId),
                cleanAction(action), payload == null ? Map.of() : payload);
    }

    static Result applyHost(PeerServerSide server, String actorId, String action,
                            Map<String,Object> payload) {
        if (server == null) return Result.fail("Authoritative server is unavailable.");
        Result result = apply(server.world, actorId, PlayerRegistry.baseName(actorId),
                cleanAction(action), payload == null ? Map.of() : payload);
        if (result.catalogChanged() || result.worldChanged()) {
            sendCatalogs(server);
            server.broadcastNow();
        }
        return result;
    }

    private static Result apply(World world, String actorId, String actorName,
                                String action, Map<String,Object> payload) {
        if (world == null || actorId == null || actorId.isBlank()) {
            return Result.fail("Fit request has no player identity.");
        }
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
            return Result.fail(ex.getMessage() == null
                    ? "Fit request was rejected." : ex.getMessage());
        }
    }

    private static Result publish(World world, String actorId, String actorName,
                                  Map<String,Object> payload) {
        String name = PlayerFitRules.cleanName(
                ServerSaveStore.string(payload, "name", ""));
        ShipFitSpec spec = ShipFitSpec.from(payload.get("spec"));
        PublishedFit fit = WorldFitCatalog.publish(world, actorId, actorName, name, spec);
        return Result.ok("Published " + fit.name() + " to this server.", true, false);
    }

    private static Result unpublish(World world, String actorId,
                                    Map<String,Object> payload) {
        String id = ServerSaveStore.string(payload, "publishedId", "");
        if (!WorldFitCatalog.unpublish(world, actorId, id)) {
            return Result.fail("Published fit was not found or is owned by another commander.");
        }
        return Result.ok("Published fit removed from the server catalog.", true, false);
    }

    private static Result refit(World world, String actorId,
                                Map<String,Object> payload) {
        Base preferred = ownedBase(world, actorId,
                ServerSaveStore.string(payload, "baseId", ""));
        String unitKey = ServerSaveStore.string(payload, "unitKey", "");
        Unit unit = world.units.get(unitKey);
        if (unit == null || !actorId.equals(unit.playerId)) {
            return Result.fail("Selected ship was not found.");
        }
        Candidate candidate = candidate(payload);
        RefitQueuePlanner.Result queued = RefitQueuePlanner.enqueueCustom(
                world, actorId, List.of(unit), candidate.name(), candidate.spec(),
                world.devFreeBuildFor(actorId), preferred);
        return queued.success()
                ? Result.ok(queued.message(), true, true)
                : Result.fail(queued.message());
    }

    private static Result refitClass(World world, String actorId,
                                     Map<String,Object> payload) {
        Base preferred = ownedBase(world, actorId,
                ServerSaveStore.string(payload, "baseId", ""));
        Candidate candidate = candidate(payload);
        ShipLoadoutDefinition preview = candidate.definition();
        List<Unit> eligible = new ArrayList<>();
        int already = 0;
        int reserved = 0;
        for (Unit unit : world.units.values()) {
            if (!actorId.equals(unit.playerId) || unit.hp <= 0
                    || !preview.hullId().equals(unit.shipTypeId)) continue;
            if (preview.id().equals(unit.loadoutId)) {
                already++;
                continue;
            }
            if (ProductionSystem.refitReserved(world, unit.key())) {
                reserved++;
                continue;
            }
            eligible.add(unit);
        }
        if (eligible.isEmpty()) {
            return Result.fail("No available " + Rules.ship(preview.hullId()).name
                    + " ships can be recalled. Already fitted: " + already
                    + "; already reserved: " + reserved + ".");
        }

        RefitQueuePlanner.Result queued = RefitQueuePlanner.enqueueCustom(
                world, actorId, eligible, candidate.name(), candidate.spec(),
                world.devFreeBuildFor(actorId), preferred);
        if (!queued.success()) return Result.fail(queued.message());
        world.status = queued.message() + " Already fitted: " + already
                + "; already reserved: " + reserved + ".";
        return Result.ok(world.status, true, true);
    }

    private static Result build(World world, String actorId,
                                Map<String,Object> payload) {
        Base base = ownedBase(world, actorId,
                ServerSaveStore.string(payload, "baseId", ""));
        Candidate candidate = candidate(payload);
        ShipLoadoutDefinition preview = candidate.definition();
        ShipType ship = Rules.findShip(preview.hullId());
        if (ship == null) return Result.fail("Unknown ship hull.");
        if (base.hp <= 0 || !base.type().buildableShips.contains(ship.id)) {
            return Result.fail(base.type().name + " cannot build " + ship.name + ".");
        }

        boolean free = world.devFreeBuildFor(actorId);
        if (!free && !ResearchRules.shipUnlocked(world, actorId, ship.id)) {
            ResearchTopic topic = ResearchRules.firstTopicUnlockingShip(ship.id);
            return Result.fail(ship.name + " requires research"
                    + (topic == null ? "." : ": " + topic.name + "."));
        }
        if (!free && !WeaponRules.unlocked(world, actorId, preview)) {
            return Result.fail(preview.displayName() + " requires research: "
                    + WeaponRules.missingResearchLabel(world, actorId, preview) + ".");
        }

        List<Cost> cost = WeaponRules.buildCost(ship, preview);
        if (!free && !HangarStore.canAfford(base.inventory, cost)) {
            return Result.fail("Need " + Rules.formatCost(cost) + " in "
                    + base.type().name + " hangar.");
        }

        EnumMap<Material,Double> inventoryBefore = new EnumMap<>(base.inventory);
        int queueSizeBefore = base.productionQueue.size();
        long nextJobBefore = base.nextProductionJobId;
        if (!free) HangarStore.spend(base.inventory, cost);
        ProductionJob job = ProductionSystem.enqueueShipPrepaid(base, ship, preview, !free);
        if (job == null) {
            restoreBuildState(base, inventoryBefore, queueSizeBefore, nextJobBefore);
            return Result.fail("Could not queue the custom-fit ship.");
        }

        ShipLoadoutDefinition installed;
        try {
            installed = WorldFitCatalog.registerRuntime(world, candidate.name(), candidate.spec());
            if (!installed.id().equals(preview.id())) {
                throw new IllegalStateException("Runtime fit registration changed the planned fit ID.");
            }
        } catch (RuntimeException ex) {
            restoreBuildState(base, inventoryBefore, queueSizeBefore, nextJobBefore);
            throw ex;
        }

        int position = base.productionQueue.size();
        world.status = "Queued " + ship.name + " - " + installed.displayName()
                + (position > 1 ? " at position " + position : "") + ".";
        AlertCenter.push(world, "Production queued: " + ship.name + " - "
                + installed.displayName() + ".");
        return Result.ok(world.status, true, true);
    }

    private static void restoreBuildState(Base base,
                                          EnumMap<Material,Double> inventoryBefore,
                                          int queueSizeBefore, long nextJobBefore) {
        while (base.productionQueue.size() > queueSizeBefore) {
            base.productionQueue.remove(base.productionQueue.size() - 1);
        }
        base.nextProductionJobId = nextJobBefore;
        base.inventory.clear();
        base.inventory.putAll(inventoryBefore);
    }

    private static Candidate candidate(Map<String,Object> payload) {
        String name = PlayerFitRules.cleanName(
                ServerSaveStore.string(payload, "name", "Custom Fit"));
        ShipFitSpec spec = ShipFitSpec.from(payload.get("spec"));
        return new Candidate(name, spec,
                PlayerFitRules.previewDefinition(name, spec));
    }

    private static ShipLoadoutDefinition register(World world,
                                                   Map<String,Object> payload) {
        Candidate candidate = candidate(payload);
        return WorldFitCatalog.registerRuntime(
                world, candidate.name(), candidate.spec());
    }

    private static Base ownedBase(World world, String actorId, String baseId) {
        Base base = world.bases.get(baseId);
        if (base == null || !actorId.equals(base.playerId)) {
            throw new IllegalArgumentException("Owned station was not found.");
        }
        return base;
    }

    static void sendCatalog(PeerServerSide server, ConnectionId connectionId) {
        if (server == null || connectionId == null || !connectionId.valid()) return;
        server.transport.sendOrdered("FIT_CATALOG|"
                + FitStateWire.encode(WorldFitCatalog.networkView(server.world)),
                connectionId);
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

    private static void sendResult(PeerServerSide server, ConnectionId connectionId,
                                   String requestId, boolean success, String message) {
        server.transport.sendOrdered("FIT_RESULT|" + cleanRequestId(requestId) + '|'
                + (success ? "OK" : "REJECTED") + '|' + encodeText(message),
                connectionId);
    }

    private static String cleanRequestId(String value) {
        if (value == null) return "0";
        String clean = value.replaceAll("[^A-Za-z0-9_.-]", "");
        return clean.isBlank()
                ? "0" : clean.substring(0, Math.min(64, clean.length()));
    }

    private static String cleanAction(String value) {
        if (value == null) return "";
        String clean = value.replaceAll("[^A-Za-z0-9_]", "")
                .toUpperCase(java.util.Locale.ROOT);
        return clean.substring(0, Math.min(48, clean.length()));
    }

    private static String encodeText(String value) {
        String clean = value == null ? ""
                : value.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.length() > 512) clean = clean.substring(0, 512);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(clean.getBytes(StandardCharsets.UTF_8));
    }

    private record Candidate(String name, ShipFitSpec spec,
                             ShipLoadoutDefinition definition) { }

    record Result(boolean success, boolean catalogChanged,
                  boolean worldChanged, String message) {
        static Result ok(String message, boolean catalogChanged,
                         boolean worldChanged) {
            return new Result(true, catalogChanged, worldChanged,
                    message == null ? "Fit request completed." : message);
        }

        static Result fail(String message) {
            return new Result(false, false, false,
                    message == null ? "Fit request rejected." : message);
        }
    }
}
