package com.tndmadman.rts;

final class GalaxySystemIdentity {
    private GalaxySystemIdentity() { }

    static StarSystemDefinition definitionFor(String systemId) {
        String templateId = templateId(systemId);
        return StarSystems.get(templateId);
    }

    static String templateId(String systemId) {
        if (systemId == null || systemId.isBlank()) return StarSystems.DEFAULT_SYSTEM_ID;
        for (StarSystemDefinition definition : StarSystems.options()) {
            if (systemId.equals(definition.id()) || systemId.equals(definition.id() + "_2")) return definition.id();
        }
        if (systemId.startsWith(StarSystems.PLAYER_HOME_SYSTEM_ID + "_")) return StarSystems.PLAYER_HOME_SYSTEM_ID;
        return StarSystems.DEFAULT_SYSTEM_ID;
    }

    static boolean playerHome(String systemId) {
        return systemId != null && systemId.startsWith(StarSystems.PLAYER_HOME_SYSTEM_ID + "_");
    }
}
