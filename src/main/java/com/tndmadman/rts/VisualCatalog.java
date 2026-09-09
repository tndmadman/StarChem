package com.tndmadman.rts;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Central lookup point for renderer-only authored visual definitions. */
final class VisualCatalog {
    private static final Map<String, ShipVisualDefinition> SHIPS = new LinkedHashMap<>();

    private VisualCatalog() { }

    static ShipVisualDefinition ship(ShipType type) {
        if (type == null) return ShipVisualDefinition.conventional("unknown");
        String typeId = type.id;
        ShipVisualDefinition authored = SHIPS.get(typeId);
        return authored != null ? authored : ShipVisualDefinition.conventional(typeId);
    }

    /** Allows renderer bootstrap/config code to register authored definitions without gameplay coupling. */
    static void registerShip(String shipTypeId, ShipVisualDefinition definition) {
        if (shipTypeId == null || shipTypeId.isBlank() || definition == null) return;
        SHIPS.put(shipTypeId, definition);
    }

    static String safeId(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(lower.length());
        boolean separator = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                result.append(c);
                separator = false;
            } else if (!separator && result.length() > 0) {
                result.append('-');
                separator = true;
            }
        }
        while (result.length() > 0 && result.charAt(result.length() - 1) == '-') {
            result.setLength(result.length() - 1);
        }
        return result.length() == 0 ? "unknown" : result.toString();
    }
}
