package com.tndmadman.rts;

final class GalaxySystemIdentity {
    private GalaxySystemIdentity() { }

    static StarSystemDefinition definitionFor(String systemId) {
        String templateId = templateId(systemId);
        return StarSystems.get(templateId);
    }

    static String templateId(String systemId) {
        if (systemId == null || systemId.isBlank()) return StarSystems.DEFAULT_SYSTEM_ID;
        if (systemId.startsWith(StarSystems.PLAYER_HOME_SYSTEM_ID + "_")) return StarSystems.PLAYER_HOME_SYSTEM_ID;
        for (StarSystemDefinition definition : StarSystems.options()) {
            if (matchesGeneratedId(systemId, definition.id())) return definition.id();
        }
        return StarSystems.DEFAULT_SYSTEM_ID;
    }

    static boolean playerHome(String systemId) {
        return systemId != null && systemId.startsWith(StarSystems.PLAYER_HOME_SYSTEM_ID + "_");
    }

    static String displayName(String systemId, StarSystemDefinition definition) {
        if (definition == null) return systemId == null ? "" : systemId;
        int copy = generatedCopyNumber(systemId, definition.id());
        return copy <= 1 ? definition.name() : definition.name() + " " + roman(copy);
    }

    private static int generatedCopyNumber(String systemId, String templateId) {
        if (systemId == null || templateId == null || systemId.equals(templateId)) return 1;
        String prefix = templateId + "_";
        if (!systemId.startsWith(prefix)) return 1;
        try {
            int copy = Integer.parseInt(systemId.substring(prefix.length()));
            return copy >= 2 ? copy : 1;
        } catch (NumberFormatException ex) {
            return 1;
        }
    }

    private static String roman(int value) {
        if (value <= 1) return "";
        int[] numbers = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] numerals = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder out = new StringBuilder();
        int remaining = value;
        for (int i = 0; i < numbers.length; i++) {
            while (remaining >= numbers[i]) {
                out.append(numerals[i]);
                remaining -= numbers[i];
            }
        }
        return out.toString();
    }

    private static boolean matchesGeneratedId(String systemId, String templateId) {
        if (systemId.equals(templateId)) return true;
        String prefix = templateId + "_";
        if (!systemId.startsWith(prefix) || systemId.length() == prefix.length()) return false;
        for (int i = prefix.length(); i < systemId.length(); i++) {
            if (!Character.isDigit(systemId.charAt(i))) return false;
        }
        try {
            return Integer.parseInt(systemId.substring(prefix.length())) >= 2;
        } catch (NumberFormatException ex) {
            return false;
        }
    }
}
