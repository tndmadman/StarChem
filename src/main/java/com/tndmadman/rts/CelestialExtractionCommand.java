package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Authoritative command boundary for planet/moon fracture charges. */
final class CelestialExtractionCommand {
    private CelestialExtractionCommand() { }

    static CelestialExtractionSystem.FireResult apply(World world, String playerId, String systemId, String bodyId) {
        if (world == null || playerId == null || playerId.isBlank()) {
            return new CelestialExtractionSystem.FireResult(false, "No authenticated extraction operator.");
        }
        WorldSystemState state = state(world, systemId);
        if (state == null) return new CelestialExtractionSystem.FireResult(false, "The target system is not available.");
        CelestialExtractionSystem.bindWorld(state, world);
        CelestialExtractionSystem.FireResult result = CelestialExtractionSystem.fireCharge(state, bodyId, playerId);
        if (result.fired()) SystemAudio.play(world, state.id, SoundCue.EXTRACTION_CHARGE_LAUNCH);
        return result;
    }

    static void handle(PeerServerSide server, String[] parts, ConnectionId connectionId) {
        if (server == null || parts == null || parts.length != 4 || connectionId == null || !connectionId.valid()) return;
        String playerId = server.ownerId(connectionId, parts[1]);
        server.touch(connectionId);
        if (!server.owns(connectionId, playerId) || ObserverSessions.isObserver(server.world, playerId)) return;

        String systemId = clean(parts[2], 128);
        String bodyId = clean(parts[3], 128);
        if (systemId.isBlank() || bodyId.isBlank() || !systemId.equals(server.views.view(server.world, playerId))) {
            send(server, connectionId, new CelestialExtractionSystem.FireResult(false,
                    "Extraction can only target the system you are currently viewing."));
            return;
        }

        CelestialExtractionSystem.FireResult[] result = {
                new CelestialExtractionSystem.FireResult(false, "Extraction request rejected.")
        };
        server.change(playerId, () -> result[0] = apply(server.world, playerId, systemId, bodyId));
        send(server, connectionId, result[0]);
        if (result[0].fired()) server.broadcastNow();
    }

    static String packet(String playerId, String systemId, String bodyId) {
        return "EXTRACT|" + clean(playerId, 64) + "|" + clean(systemId, 128) + "|" + clean(bodyId, 128);
    }

    private static void send(PeerServerSide server, ConnectionId connectionId,
                             CelestialExtractionSystem.FireResult result) {
        String message = result == null ? "Extraction request processed." : result.message();
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(message.getBytes(StandardCharsets.UTF_8));
        server.transport.sendOrdered("EXTRACTION_RESULT|" + (result != null && result.fired() ? "1" : "0")
                + "|" + encoded, connectionId);
    }

    private static WorldSystemState state(World world, String systemId) {
        if (world == null || systemId == null || systemId.isBlank()) return null;
        for (WorldSystemState state : world.policySystemStates()) {
            if (state != null && systemId.equals(state.id)) return state;
        }
        return null;
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String cleaned = value.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
    }
}
