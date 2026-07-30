package com.tndmadman.rts;

/** Authenticated server command for live player-to-player diplomacy. */
final class DiplomacyCommand {
    private DiplomacyCommand() { }

    static void handle(PeerServerSide server, String[] parts, ConnectionId connectionId, String actorId) {
        if (server == null || connectionId == null || !connectionId.valid()) return;
        server.touch(connectionId);
        if (!server.owns(connectionId, actorId)) return;
        if (parts == null || parts.length != 4) {
            server.transport.recordMalformedPacket();
            return;
        }

        String targetId = cleanOwner(parts[2]);
        DiplomacySystem.LiveAction action;
        try {
            action = DiplomacySystem.LiveAction.valueOf(parts[3]);
        } catch (RuntimeException ex) {
            server.transport.recordMalformedPacket();
            return;
        }
        if (!knownHuman(targetId) || actorId.equals(targetId)) {
            sendNotice(server, actorId, "Diplomacy target is not an available human player.");
            return;
        }

        DiplomacySystem.LiveResult[] result = { DiplomacySystem.LiveResult.INVALID_TARGET };
        server.change(actorId, () -> result[0] = DiplomacySystem.applyLiveAction(
                server.world, actorId, targetId, action));

        if (result[0].changed()) {
            DiplomacyBootstrap.refreshIntelAlliances(server.world);
            broadcastState(server);
            server.broadcastNow();
        }
        notifyResult(server, actorId, targetId, result[0]);
    }

    static DiplomacySystem.LiveResult applyLocal(World world, String actorId, String targetId,
                                                  DiplomacySystem.LiveAction action) {
        DiplomacySystem.LiveResult result = DiplomacySystem.applyLiveAction(world, actorId, targetId, action);
        if (result.changed()) DiplomacyBootstrap.refreshIntelAlliances(world);
        return result;
    }

    private static void broadcastState(PeerServerSide server) {
        String packet = SkirmishRuntime.settings(server.world).packet();
        for (PlayerInfo player : PlayerRegistry.snapshotPlayers()) {
            if (player == null || player.id() == null || player.id().isBlank()) continue;
            ConnectionId connectionId = server.connectionIdForPlayer(player.id());
            if (connectionId.valid()) server.transport.sendOrdered(packet, connectionId);
        }
    }

    private static void notifyResult(PeerServerSide server, String actorId, String targetId,
                                     DiplomacySystem.LiveResult result) {
        String actorName = PlayerRegistry.baseName(actorId);
        String targetName = PlayerRegistry.baseName(targetId);
        switch (result) {
            case ALLIANCE_OFFERED -> {
                sendNotice(server, actorId, "Alliance offer sent to " + targetName + ".");
                sendNotice(server, targetId, actorName
                        + " offered an alliance. Open Diplomacy and choose Ally to accept.");
            }
            case ALLIANCE_ACCEPTED -> {
                sendNotice(server, actorId, "Alliance formed with " + targetName + ".");
                sendNotice(server, targetId, "Alliance formed with " + actorName + ".");
            }
            case NEUTRAL_SET -> {
                sendNotice(server, actorId, "Relationship with " + targetName + " is now neutral.");
                sendNotice(server, targetId, actorName + " set your relationship to neutral.");
            }
            case HOSTILE_SET -> {
                sendNotice(server, actorId, "Relationship with " + targetName + " is now hostile.");
                sendNotice(server, targetId, actorName + " declared your relationship hostile.");
            }
            case MODE_LOCKED -> sendNotice(server, actorId,
                    "Live diplomacy is unavailable because this match uses fixed or locked teams.");
            case UNCHANGED -> {
                if (DiplomacySystem.hasAllianceOffer(server.world, actorId, targetId)) {
                    sendNotice(server, actorId, "Alliance offer to " + targetName + " is already pending.");
                } else {
                    sendNotice(server, actorId, "That diplomacy relationship is already active.");
                }
            }
            case INVALID_TARGET -> sendNotice(server, actorId, "Diplomacy request was rejected.");
        }
    }

    private static void sendNotice(PeerServerSide server, String playerId, String message) {
        ConnectionId connectionId = server.connectionIdForPlayer(playerId);
        if (connectionId.valid()) server.transport.sendOrdered("SERVER_NOTICE|" + cleanText(message), connectionId);
    }

    private static boolean knownHuman(String playerId) {
        if (playerId == null || playerId.isBlank() || NpcRules.isNpcFaction(playerId)) return false;
        for (PlayerInfo player : PlayerRegistry.snapshotPlayers()) {
            if (player != null && playerId.equals(player.id())) return true;
        }
        return false;
    }

    private static String cleanOwner(String value) {
        if (value == null) return "";
        String clean = value.replace("|", "").trim();
        return clean.length() <= 64 ? clean : clean.substring(0, 64);
    }

    private static String cleanText(String value) {
        if (value == null) return "";
        String clean = value.replace('|', '/').replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= 512 ? clean : clean.substring(0, 512);
    }
}
