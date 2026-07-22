package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Produces bounded packet-failure diagnostics without exposing packet field values. */
final class PacketDiagnostics {
    private static final int MAX_PACKET_TYPE_CHARS = 24;
    private static final int MAX_ERROR_TYPE_CHARS = 64;
    private static final int MAX_REPORTED_FIELDS = 10_000;

    private PacketDiagnostics() { }

    static String rejectedInbound(String message, NetPacket packet, Exception error) {
        ConnectionId connectionId = packet == null ? ConnectionId.NONE : packet.connectionId();
        String remoteAddress = packet == null || packet.address() == null
                ? "unknown" : packet.address().getHostAddress();
        int remotePort = packet == null ? 0 : packet.port();
        int payloadBytes = message == null ? 0 : message.getBytes(StandardCharsets.UTF_8).length;
        String endpoint = remotePort > 0 ? remoteAddress + ':' + remotePort : remoteAddress;
        String errorType = safeToken(error == null ? "Exception" : error.getClass().getSimpleName(),
                MAX_ERROR_TYPE_CHARS);
        return "Rejected inbound packet type=" + packetType(message)
                + " connection=" + connectionId
                + " remote=" + endpoint
                + " fields=" + fieldCount(message)
                + " bytes=" + payloadBytes
                + " error=" + errorType;
    }

    private static String packetType(String message) {
        if (message == null || message.isBlank()) return "UNKNOWN";
        int delimiter = message.indexOf('|');
        String candidate = delimiter < 0 ? message : message.substring(0, delimiter);
        if (candidate.isBlank() || candidate.length() > MAX_PACKET_TYPE_CHARS) return "UNKNOWN";
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (!(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z')
                    && !(c >= '0' && c <= '9') && c != '_' && c != '-') return "UNKNOWN";
        }
        return candidate.toUpperCase(Locale.ROOT);
    }

    private static int fieldCount(String message) {
        if (message == null || message.isEmpty()) return 0;
        int count = 1;
        for (int i = 0; i < message.length() && count < MAX_REPORTED_FIELDS; i++) {
            if (message.charAt(i) == '|') count++;
        }
        return count;
    }

    private static String safeToken(String value, int maxChars) {
        if (value == null || value.isBlank()) return "Exception";
        StringBuilder out = new StringBuilder(Math.min(value.length(), maxChars));
        for (int i = 0; i < value.length() && out.length() < maxChars; i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$') out.append(c);
        }
        return out.isEmpty() ? "Exception" : out.toString();
    }
}
