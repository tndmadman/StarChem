package com.tndmadman.rts;

final class NpcSystemScope {
    private NpcSystemScope() { }

    static boolean allows(String systemId, String factionId) {
        if (factionId == null || factionId.isBlank()) return false;
        if (GalaxySystemIdentity.playerHome(systemId)) {
            return Config.RAIDERS_ID.equals(factionId) || Config.FREE_MINERS_ID.equals(factionId);
        }

        StarSystemDefinition definition = GalaxySystemIdentity.definitionFor(systemId);
        if (Config.CORSAIRS_ID.equals(factionId)) return allowsCorsairs(definition);
        if (Config.FREE_MINERS_ID.equals(factionId)) return allowsMiners(definition);
        if (Config.RAIDERS_ID.equals(factionId)) return allowsRaiders(definition);
        return true;
    }

    private static boolean allowsCorsairs(StarSystemDefinition definition) {
        return definition.id().equals(StarSystems.CORSAIR_SYSTEM_ID)
                || definition.hasTag("relics")
                || definition.hasTag("high_value")
                || definition.hasTag("contested");
    }

    private static boolean allowsMiners(StarSystemDefinition definition) {
        return definition.hasTag("metal_rich")
                || definition.hasTag("industrial")
                || definition.hasTag("gas_rich")
                || definition.hasTag("chemical")
                || definition.hasTag("mixed_resources")
                || definition.hasTag("frontier")
                || definition.role().equalsIgnoreCase("standard")
                || definition.role().equalsIgnoreCase("industrial")
                || definition.role().equalsIgnoreCase("gas")
                || definition.role().equalsIgnoreCase("ice")
                || definition.role().equalsIgnoreCase("chemical");
    }

    private static boolean allowsRaiders(StarSystemDefinition definition) {
        return definition.hasTag("contested")
                || definition.hasTag("hazardous")
                || definition.hasTag("high_value")
                || definition.hasTag("frontier")
                || definition.role().equalsIgnoreCase("danger")
                || definition.role().equalsIgnoreCase("warzone")
                || definition.role().equalsIgnoreCase("hazard")
                || definition.role().equalsIgnoreCase("strategic")
                || definition.role().equalsIgnoreCase("relic")
                || definition.role().equalsIgnoreCase("salvage");
    }
}
