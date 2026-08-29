package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Compact rows embedded in the existing GALAXY state packet. */
final class GalaxyEventWire {
    static final int MAX_EVENT_VIEWS = 128;
    private static final int MAX_TEXT = 160;

    private GalaxyEventWire() { }

    static List<String> encodeRows(World world, String playerId) {
        List<String> out = new ArrayList<>();
        for (GalaxyEventView view : GalaxyEventDirector.viewsFor(world, playerId)) {
            if (view == null) continue;
            out.add("V," + token(view.eventId())
                    + "," + token(view.definitionId())
                    + "," + view.kind().name()
                    + "," + token(view.systemId())
                    + "," + token(view.name())
                    + "," + view.phase().name()
                    + "," + finite(view.x())
                    + "," + finite(view.y())
                    + "," + finite(Math.max(0, view.remainingSeconds())));
            if (out.size() >= MAX_EVENT_VIEWS) break;
        }
        return List.copyOf(out);
    }

    static GalaxyEventView decodeRow(String row) {
        if (row == null || !row.startsWith("V,")) throw new SnapshotDecodeException("Malformed galaxy event row.");
        String[] f = row.split(",", -1);
        if (f.length != 10) throw new SnapshotDecodeException("Malformed galaxy event row.");
        String eventId = text(f[1]);
        String definitionId = text(f[2]);
        GalaxyEventKind kind = enumValue(GalaxyEventKind.class, f[3], "galaxy event kind");
        String systemId = text(f[4]);
        String name = text(f[5]);
        GalaxyEventPhase phase = enumValue(GalaxyEventPhase.class, f[6], "galaxy event phase");
        double x = number(f[7], -1_000_000_000, 1_000_000_000, "galaxy event x");
        double y = number(f[8], -1_000_000_000, 1_000_000_000, "galaxy event y");
        double remaining = number(f[9], 0, 31_536_000, "galaxy event remaining time");
        if (eventId.isBlank() || definitionId.isBlank() || systemId.isBlank()
                || eventId.length() > MAX_TEXT || definitionId.length() > MAX_TEXT
                || systemId.length() > MAX_TEXT || name.length() > MAX_TEXT) {
            throw new SnapshotDecodeException("Malformed galaxy event identity.");
        }
        return new GalaxyEventView(eventId, definitionId, kind, systemId, name, phase, x, y, remaining);
    }

    private static String token(String value) {
        String safe = clean(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(safe.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(String token) {
        try {
            if (token == null || token.isBlank()) return "";
            return clean(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
        } catch (RuntimeException ex) {
            throw new SnapshotDecodeException("Malformed galaxy event text token.");
        }
    }

    private static double number(String value, double min, double max, String label) {
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed) || parsed < min || parsed > max) throw new NumberFormatException();
            return parsed;
        } catch (RuntimeException ex) {
            throw new SnapshotDecodeException("Malformed " + label + ".");
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String label) {
        try { return Enum.valueOf(type, value); }
        catch (RuntimeException ex) { throw new SnapshotDecodeException("Malformed " + label + "."); }
    }

    private static String finite(double value) {
        return Double.toString(Double.isFinite(value) ? value : 0);
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
