package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Authenticated server command and recipient-scoped synchronization for live diplomacy. */
final class DiplomacyCommand {
    private static final Map<World,Long> REVISIONS = Collections.synchronizedMap(new WeakHashMap<>());

    private DiplomacyCommand() { }

    /**
     * Packet shape: DIPLOMACY|requestId|targetId|action.
     * The actor is intentionally absent and is derived only from the authenticated connection.
     */
    static void handle(PeerServerSide server, String[] parts, ConnectionId connectionId) {
        if (server == null || connectionId == null || !connectionId.valid()) return;
        server.touch(connectionId);
        String actorId = server.ownerId(connectionId, "");
        if (actorId.isBlank() || !server.owns(connectionId, actorId)) {
            server.transport.recordMalformedPacket();
            return;
        }
        if (parts == null || parts.length != 4) {
            server.transport.recordMalformedPacket();
            sendResult(server, connectionId, requestId(parts), "REJECTED", "Malformed diplomacy request.");
            return;
        }

        String requestId = cleanRequestId(parts[1]);
        String targetId = cleanOwner(parts[2]);
        String actionName = parts[3] == null ? "" : parts[3].trim();
        if ("REFRESH".equals(actionName)) {
            sendView(server, actorId, connectionId);
            return;
        }

        DiplomacySystem.LiveAction action;
        try {
            action = DiplomacySystem.LiveAction.valueOf(actionName);
        } catch (RuntimeException ex) {
            server.transport.recordMalformedPacket();
            sendResult(server, connectionId, requestId, "REJECTED", "Unknown diplomacy action.");
            return;
        }

        if (actorId.equals(targetId) || !knownHuman(server, targetId)) {
            sendResult(server, connectionId, requestId, "INVALID_TARGET",
                    "Diplomacy target is not a retained human player.");
            sendView(server, actorId, connectionId);
            return;
        }

        DiplomacySystem.LiveResult[] result = { DiplomacySystem.LiveResult.INVALID_TARGET };
        server.change(actorId, () -> result[0] = DiplomacySystem.applyLiveAction(
                server.world, actorId, targetId, action));

        if (result[0].changed()) {
            bumpRevision(server.world);
            DiplomacyBootstrap.refreshIntelAlliances(server.world);
            notifyParticipants(server, actorId, targetId, action, result[0]);
            sendViews(server);
            server.broadcastNow();
        } else {
            sendView(server, actorId, connectionId);
        }
        sendResult(server, connectionId, requestId, result[0].name(),
                resultMessage(server, actorId, targetId, action, result[0]));
    }

    static DiplomacySystem.LiveResult applyLocal(World world, String actorId, String targetId,
                                                  DiplomacySystem.LiveAction action) {
        DiplomacySystem.LiveResult result = DiplomacySystem.applyLiveAction(world, actorId, targetId, action);
        if (result.changed()) {
            bumpRevision(world);
            DiplomacyBootstrap.refreshIntelAlliances(world);
        }
        return result;
    }

    static Map<String,Object> viewForTest(PeerServerSide server, String playerId) {
        return buildView(server, playerId);
    }

    static void sendView(PeerServerSide server, String playerId, ConnectionId connectionId) {
        if (server == null || playerId == null || playerId.isBlank()
                || connectionId == null || !connectionId.valid()) return;
        server.transport.sendOrdered("DIPLOMACY_VIEW|"
                + DiplomacyStateWire.encode(buildView(server, playerId)), connectionId);
    }

    private static void sendViews(PeerServerSide server) {
        for (PersistentPlayerSession session : server.persistentSessions()) {
            if (session == null || session.playerId() == null || session.playerId().isBlank()) continue;
            ConnectionId connectionId = server.connectionIdForPlayer(session.playerId());
            if (connectionId.valid()) sendView(server, session.playerId(), connectionId);
        }
    }

    private static Map<String,Object> buildView(PeerServerSide server, String playerId) {
        Map<String,Object> root = new LinkedHashMap<>();
        root.put("revision", revision(server.world));
        root.put("selfId", playerId);
        root.put("mode", DiplomacySystem.mode(server.world).name());
        root.put("negotiationAllowed", DiplomacySystem.liveNegotiationAllowed(server.world));

        Map<String,Object> state = new LinkedHashMap<>(DiplomacySystem.capture(server.world));
        List<Object> scopedOffers = new ArrayList<>();
        for (Object value : ServerSaveStore.list(state.get("allianceOffers"))) {
            Map<String,Object> offer = ServerSaveStore.object(value);
            String from = ServerSaveStore.string(offer, "from", "");
            String to = ServerSaveStore.string(offer, "to", "");
            if (!playerId.equals(from) && !playerId.equals(to)) continue;
            Map<String,Object> copy = new LinkedHashMap<>();
            copy.put("from", from);
            copy.put("to", to);
            scopedOffers.add(copy);
        }
        state.put("allianceOffers", scopedOffers);
        root.put("state", state);

        List<Object> players = new ArrayList<>();
        for (PersistentPlayerSession session : server.persistentSessions()) {
            if (session == null || session.playerId() == null || session.playerId().isBlank()
                    || playerId.equals(session.playerId()) || NpcRules.isNpcFaction(session.playerId())) continue;
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id", session.playerId());
            row.put("name", session.name());
            row.put("rgb", session.rgb());
            row.put("online", server.sessionConnected(session.playerId()));
            row.put("relationship", DiplomacySystem.relationship(
                    server.world, playerId, session.playerId()).name());
            row.put("incomingOffer", DiplomacySystem.hasAllianceOffer(
                    server.world, session.playerId(), playerId));
            row.put("outgoingOffer", DiplomacySystem.hasAllianceOffer(
                    server.world, playerId, session.playerId()));
            players.add(row);
        }
        root.put("players", players);
        return root;
    }

    private static void notifyParticipants(PeerServerSide server, String actorId, String targetId,
                                           DiplomacySystem.LiveAction action,
                                           DiplomacySystem.LiveResult result) {
        String actorName = playerName(server, actorId);
        String targetName = playerName(server, targetId);
        String targetMessage = switch (result) {
            case ALLIANCE_OFFERED -> actorName + " offered you an alliance. Open Diplomacy to accept or decline.";
            case ALLIANCE_ACCEPTED -> "Alliance formed with " + actorName + ".";
            case ALLIANCE_DECLINED -> actorName + " declined your alliance offer.";
            case ALLIANCE_CANCELED -> actorName + " canceled their alliance offer.";
            case NEUTRAL_SET -> actorName + " set your relationship to neutral.";
            case HOSTILE_SET -> actorName + " declared your relationship hostile.";
            default -> "";
        };
        if (!targetMessage.isBlank()) {
            GameNoticeCenter.publish(server.world, targetId, NoticeCategory.SYSTEM, targetMessage, false);
        }

        if (result == DiplomacySystem.LiveResult.ALLIANCE_ACCEPTED) {
            GameNoticeCenter.publish(server.world, actorId, NoticeCategory.SYSTEM,
                    "Alliance formed with " + targetName + ".", false);
        }
    }

    private static String resultMessage(PeerServerSide server, String actorId, String targetId,
                                        DiplomacySystem.LiveAction action,
                                        DiplomacySystem.LiveResult result) {
        String targetName = playerName(server, targetId);
        return switch (result) {
            case ALLIANCE_OFFERED -> "Alliance offer sent to " + targetName
                    + (server.sessionConnected(targetId) ? "." : "; they will receive it when they reconnect.");
            case ALLIANCE_ACCEPTED -> "Alliance formed with " + targetName + ".";
            case ALLIANCE_DECLINED -> "Alliance offer from " + targetName + " declined.";
            case ALLIANCE_CANCELED -> "Alliance offer to " + targetName + " canceled.";
            case NEUTRAL_SET -> "Relationship with " + targetName + " is now neutral.";
            case HOSTILE_SET -> "Relationship with " + targetName + " is now hostile.";
            case NO_PENDING_OFFER -> action == DiplomacySystem.LiveAction.ACCEPT_ALLIANCE
                    || action == DiplomacySystem.LiveAction.DECLINE_ALLIANCE
                    ? "There is no incoming alliance offer from " + targetName + "."
                    : "There is no outgoing alliance offer to " + targetName + ".";
            case MODE_LOCKED -> "Live diplomacy is unavailable because this match uses controlled teams.";
            case UNCHANGED -> "That diplomacy state is already active.";
            case INVALID_TARGET -> "Diplomacy request was rejected.";
        };
    }

    private static void sendResult(PeerServerSide server, ConnectionId connectionId,
                                   String requestId, String result, String message) {
        if (server == null || connectionId == null || !connectionId.valid()) return;
        server.transport.sendOrdered("DIPLOMACY_RESULT|" + cleanRequestId(requestId) + '|'
                + cleanResult(result) + '|' + encodeText(message), connectionId);
    }

    private static boolean knownHuman(PeerServerSide server, String playerId) {
        if (server == null || playerId == null || playerId.isBlank() || NpcRules.isNpcFaction(playerId)) return false;
        for (PersistentPlayerSession session : server.persistentSessions()) {
            if (session != null && playerId.equals(session.playerId())) return true;
        }
        return false;
    }

    private static String playerName(PeerServerSide server, String playerId) {
        for (PersistentPlayerSession session : server.persistentSessions()) {
            if (session != null && playerId.equals(session.playerId())) return Config.clean(session.name());
        }
        return playerId;
    }

    private static synchronized long bumpRevision(World world) {
        long next = Math.max(1, REVISIONS.getOrDefault(world, 0L) + 1);
        REVISIONS.put(world, next);
        return next;
    }

    private static synchronized long revision(World world) {
        return REVISIONS.getOrDefault(world, 0L);
    }

    private static String requestId(String[] parts) {
        return parts != null && parts.length > 1 ? cleanRequestId(parts[1]) : "0";
    }

    private static String cleanRequestId(String value) {
        if (value == null) return "0";
        String clean = value.replaceAll("[^A-Za-z0-9_.-]", "");
        if (clean.isBlank()) return "0";
        return clean.length() <= 64 ? clean : clean.substring(0, 64);
    }

    private static String cleanOwner(String value) {
        if (value == null) return "";
        String clean = value.replace("|", "").trim();
        return clean.length() <= 64 ? clean : clean.substring(0, 64);
    }

    private static String cleanResult(String value) {
        if (value == null) return "REJECTED";
        String clean = value.replaceAll("[^A-Za-z0-9_]", "");
        return clean.isBlank() ? "REJECTED" : clean.substring(0, Math.min(48, clean.length()));
    }

    private static String encodeText(String value) {
        String clean = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.length() > 512) clean = clean.substring(0, 512);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(clean.getBytes(StandardCharsets.UTF_8));
    }
}
