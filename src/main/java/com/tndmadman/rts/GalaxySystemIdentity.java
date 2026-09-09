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
