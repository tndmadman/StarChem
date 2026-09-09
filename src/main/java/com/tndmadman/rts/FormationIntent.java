package com.tndmadman.rts;

import java.util.Locale;

/**
 * Compact formation metadata carried inside existing queued-command string fields.
 * This intentionally reuses the current wire format so older queue semantics and
 * save/network serialization remain unchanged.
 */
record FormationIntent(String token, FleetFormation formation, double forwardX, double forwardY) {
    static final String PREFIX = "@F2|";
    private static final int MAX_TOKEN_LENGTH = 48;

    FormationIntent {
        token = token == null ? "" : token.trim();
        formation = formation == null ? FleetFormation.GRID : formation;
        double length = Math.hypot(forwardX, forwardY);
        if (!Double.isFinite(length) || length < 1.0e-9) {
            forwardX = 0;
            forwardY = -1;
        } else {
            forwardX /= length;
            forwardY /= length;
        }
    }

    static String encode(String token, FleetFormation formation, double forwardX, double forwardY) {
        String clean = token == null ? "" : token.trim();
        if (!validToken(clean)) throw new IllegalArgumentException("Invalid formation token.");
        FleetFormation resolved = formation == null ? FleetFormation.GRID : formation;
        double length = Math.hypot(forwardX, forwardY);
        if (!Double.isFinite(length) || length < 1.0e-9) {
            forwardX = 0;
            forwardY = -1;
        } else {
            forwardX /= length;
            forwardY /= length;
        }
        return String.format(Locale.ROOT, "%s%s|%s|%.8f|%.8f",
                PREFIX, clean, resolved.name(), forwardX, forwardY);
    }

    static boolean claims(QueuedUnitCommand command) {
        return command != null && command.targetKey() != null && command.targetKey().startsWith(PREFIX);
    }

    static FormationIntent parse(QueuedUnitCommand command) {
        return command == null ? null : parse(command.targetKey());
    }

    static FormationIntent parse(String encoded) {
        if (encoded == null || !encoded.startsWith(PREFIX) || encoded.length() > 256) return null;
        String[] parts = encoded.split("\\|", -1);
        if (parts.length != 5 || !"@F2".equals(parts[0]) || !validToken(parts[1])) return null;
        final FleetFormation formation;
        final double forwardX;
        final double forwardY;
        try {
            formation = FleetFormation.valueOf(parts[2]);
            forwardX = Double.parseDouble(parts[3]);
            forwardY = Double.parseDouble(parts[4]);
        } catch (RuntimeException ex) {
            return null;
        }
        if (!Double.isFinite(forwardX) || !Double.isFinite(forwardY)) return null;
        double length = Math.hypot(forwardX, forwardY);
        if (!Double.isFinite(length) || length < 0.5 || length > 1.5) return null;
        return new FormationIntent(parts[1], formation, forwardX, forwardY);
    }

    static boolean structurallyValid(QueuedUnitCommand command) {
        if (!claims(command)) return true;
        if (command.kind() != QueuedCommandKind.MOVE && command.kind() != QueuedCommandKind.WORMHOLE) return false;
        return parse(command) != null
                && GameplayCommandNumbers.finite(command.x1(), command.y1(), command.x2(), command.y2());
    }

    private static boolean validToken(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) return false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.')) return false;
        }
        return true;
    }
}
