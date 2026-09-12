package com.tndmadman.rts;

/** Player-facing explanations for what is required to complete each celestial objective. */
final class CelestialObjectiveHelp {
    private CelestialObjectiveHelp() { }

    static String requirement(WorldSystemState state, String bodyId, CelestialObjectiveType objective) {
        if (objective == null) return "";
        CelestialSystem.BodyView body = state == null || state.celestials == null
                ? null : state.celestials.bodyView(bodyId);
        boolean moon = body != null && body.moon();
        String masterName = masterName(state, bodyId);

        return switch (objective) {
            case SCAN -> "Station needed: none. Keep any ship or orbital station in scan range for "
                    + Math.round(CelestialGameplaySystem.SCAN_SECONDS)
                    + " seconds. A Sensor Array is useful, but is not required.";
            case CLAIM -> moon
                    ? "Moon sovereignty is inherited from " + masterName
                    + ". Claim the master planet with any friendly orbital station; no separate moon claim station is required."
                    : "Station needed: any friendly orbital station. The first accepted anchor starts the claim; hostile and neutral factions are locked out once anchoring begins.";
            case HOLD -> moon
                    ? "Hold is inherited from " + masterName
                    + ". Keep the master planet claimed, uncontested, and supported by a friendly orbital station for "
                    + Math.round(CelestialGameplaySystem.HOLD_OBJECTIVE_SECONDS) + " seconds."
                    : "Station needed: keep at least one friendly orbital station anchored. Hold the planet uncontested for "
                    + Math.round(CelestialGameplaySystem.HOLD_OBJECTIVE_SECONDS) + " seconds.";
            case EXTRACT -> "Station needed: none for objective progress. Mine "
                    + Math.round(CelestialGameplaySystem.EXTRACT_OBJECTIVE_AMOUNT)
                    + " units from this body's deposits. A Manufacturing/Extractor station is required to activate its mining bonus.";
        };
    }

    private static String masterName(WorldSystemState state, String bodyId) {
        if (state == null || state.celestials == null) return "the parent planet";
        String masterId = CelestialMoonInheritance.masterPlanetId(state, bodyId);
        CelestialSystem.BodyView master = masterId.isBlank() ? null : state.celestials.bodyView(masterId);
        return master == null || master.name() == null || master.name().isBlank()
                ? "the parent planet" : master.name();
    }
}
