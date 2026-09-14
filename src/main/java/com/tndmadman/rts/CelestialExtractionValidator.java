package com.tndmadman.rts;

import java.util.Map;
import java.util.Random;

/** Regression coverage for extractor-gated planet/moon deposits and fracture charges. */
final class CelestialExtractionValidator {
    private CelestialExtractionValidator() { }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        depositsStayAbsentUntilChargeImpact();
        oneMasterExtractorCanFractureSlaveMoons();
        releasedBodiesPersist();
        System.out.println("Celestial extraction validation passed.");
    }

    private static void depositsStayAbsentUntilChargeImpact() {
        WorldSystemState state = state("extract-gate", 9101L);
        CelestialSystem.BodyView planet = planetWithMoon(state);
        CelestialBodyState body = CelestialGameplaySystem.bodyState(state, planet.id());
        require(body != null, "planet body state missing");

        state.celestials.update(0);
        require(body.resourceNodeIds.isEmpty(), "unfractured body must not expose physical resource ids");
        require(countAnchored(state, planet.id()) == 0, "unfractured body must not contain physical resource nodes");

        Base extractor = new Base("P1:EXTRACTOR", "P1", CelestialExtractionSystem.EXTRACTOR_STATION_ID,
                planet.x() + planet.radius() + 145, planet.y());
        state.bases.put(extractor.id, extractor);
        state.celestials.update(0);
        require(planet.id().equals(extractor.celestialAnchorBodyId), "extractor must anchor to the master planet");
        require(CelestialExtractionSystem.extractorReady(state, planet.id(), "P1"),
                "anchored claimant extractor must be ready to fire");
        require(body.resourceNodeIds.isEmpty(), "deploying extractor alone must not create rocks");

        CelestialExtractionSystem.FireResult fired = CelestialExtractionSystem.fireCharge(state, planet.id(), "P1");
        require(fired.fired(), "extractor must fire a fracture charge at its claimed planet");
        state.celestials.update(CelestialExtractionSystem.CHARGE_SECONDS * 0.5);
        require(!CelestialExtractionSystem.released(state, planet.id()), "body must remain sealed while charge is in flight");
        require(body.resourceNodeIds.isEmpty(), "rocks must remain absent before charge impact");

        state.celestials.update(CelestialExtractionSystem.CHARGE_SECONDS);
        require(CelestialExtractionSystem.released(state, planet.id()), "charge impact must release the target body");
        require(!body.resourceNodeIds.isEmpty(), "released body must expose deterministic resource ids");
        require(countAnchored(state, planet.id()) == body.resourceNodeIds.size(),
                "released body resource ids must resolve to physical anchored nodes");
    }

    private static void oneMasterExtractorCanFractureSlaveMoons() {
        WorldSystemState state = state("extract-moon", 9102L);
        CelestialSystem.BodyView planet = planetWithMoon(state);
        CelestialSystem.BodyView moon = firstMoonOf(state, planet.id());
        require(moon != null, "fixture requires a slave moon");

        Base extractor = new Base("P1:MOON-EXTRACTOR", "P1", CelestialExtractionSystem.EXTRACTOR_STATION_ID,
                planet.x() + planet.radius() + 145, planet.y());
        state.bases.put(extractor.id, extractor);
        state.celestials.update(0);
        require(planet.id().equals(extractor.celestialAnchorBodyId), "extractor must remain on the master planet");
        require(CelestialExtractionSystem.extractorReady(state, moon.id(), "P1"),
                "master-planet extractor must be able to service a slave moon");

        CelestialExtractionSystem.FireResult fired = CelestialExtractionSystem.fireCharge(state, moon.id(), "P1");
        require(fired.fired(), "master extractor must fire a charge at a slave moon");
        state.celestials.update(CelestialExtractionSystem.CHARGE_SECONDS + 0.05);

        CelestialBodyState moonState = CelestialGameplaySystem.bodyState(state, moon.id());
        require(CelestialExtractionSystem.released(state, moon.id()), "moon charge must release only the targeted moon");
        require(moonState != null && !moonState.resourceNodeIds.isEmpty(), "fractured moon must expose deposits");
        require(!CelestialExtractionSystem.released(state, planet.id()),
                "fracturing a moon must not automatically fracture its master planet");
    }

    private static void releasedBodiesPersist() {
        WorldSystemState state = state("extract-persist", 9103L);
        CelestialSystem.BodyView planet = planetWithMoon(state);
        Base extractor = new Base("P1:PERSIST-EXTRACTOR", "P1", CelestialExtractionSystem.EXTRACTOR_STATION_ID,
                planet.x() + planet.radius() + 145, planet.y());
        state.bases.put(extractor.id, extractor);
        state.celestials.update(0);
        require(CelestialExtractionSystem.fireCharge(state, planet.id(), "P1").fired(), "persistence charge must fire");
        state.celestials.update(CelestialExtractionSystem.CHARGE_SECONDS + 0.05);
        Map<String,Object> saved = CelestialExtractionSystem.captureState(state);

        WorldSystemState restored = state("extract-persist", 9103L);
        CelestialExtractionSystem.restoreState(restored, saved);
        restored.celestials.update(0);
        require(CelestialExtractionSystem.released(restored, planet.id()), "released body flag must survive save/restore");
        CelestialBodyState restoredBody = CelestialGameplaySystem.bodyState(restored, planet.id());
        require(restoredBody != null && !restoredBody.resourceNodeIds.isEmpty(),
                "restored released body must regenerate its deterministic physical deposits");
    }

    private static WorldSystemState state(String id, long seed) {
        StarSystemDefinition definition = StarSystems.defaultSystem();
        CelestialSystem celestials = new CelestialSystem(definition, new Random(seed));
        return new WorldSystemState(id, definition, celestials);
    }

    private static CelestialSystem.BodyView planetWithMoon(WorldSystemState state) {
        for (CelestialSystem.BodyView body : state.celestials.bodyViews()) {
            if (body.visualClass() == CelestialVisualClass.STAR || body.moon()) continue;
            if (firstMoonOf(state, body.id()) != null) return body;
        }
        throw new IllegalStateException("fixture requires a planet with at least one moon");
    }

    private static CelestialSystem.BodyView firstMoonOf(WorldSystemState state, String planetId) {
        for (CelestialSystem.BodyView body : state.celestials.bodyViews()) {
            if (body.moon() && planetId.equals(body.parentId())) return body;
        }
        return null;
    }

    private static int countAnchored(WorldSystemState state, String bodyId) {
        int count = 0;
        for (ResourceNode node : state.resources) if (bodyId.equals(node.celestialAnchorBodyId)) count++;
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
