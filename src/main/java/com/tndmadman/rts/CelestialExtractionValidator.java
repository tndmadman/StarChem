package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Regression coverage for extractor-gated planet/moon deposits and fracture charges. */
final class CelestialExtractionValidator {
    private CelestialExtractionValidator() { }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        depositsStayAbsentUntilChargeImpact();
        oneMasterExtractorCanFractureSlaveMoons();
        exhaustedFieldRequiresCooldownAndRefiresWithoutNodeChurn();
        releasedBodiesPersist();
        System.out.println("Celestial extraction validation passed.");
    }

    private static void depositsStayAbsentUntilChargeImpact() {
        WorldSystemState state = state("extract-gate", 9101L);
        CelestialSystem.BodyView planet = planetWithMoon(state);
        CelestialBodyState body = CelestialGameplaySystem.bodyState(state, planet.id());
        require(body != null, "planet body state missing");

        state.celestials.update(0);
        int expectedRocks = body.profile.deposits().size() * CelestialExtractionSystem.FRAGMENTS_PER_DEPOSIT;
        require(body.resourceNodeIds.size() == expectedRocks,
                "fracture field must preallocate ten mineables per surveyed material");
        require(countActiveAnchored(state, planet.id()) == 0,
                "unfractured body must not expose active physical resource nodes");
        require(Math.abs(totalMaxVolume(state, planet.id()) - expectedVolume(body)) < 0.001,
                "ten-times denser fracture field must preserve the original total resource volume");

        Base extractor = new Base("P1:EXTRACTOR", "P1", CelestialExtractionSystem.EXTRACTOR_STATION_ID,
                planet.x() + planet.radius() + 145, planet.y());
        state.bases.put(extractor.id, extractor);
        state.celestials.update(0);
        require(planet.id().equals(extractor.celestialAnchorBodyId), "extractor must anchor to the master planet");
        require(CelestialExtractionSystem.extractorReady(state, planet.id(), "P1"),
                "anchored claimant extractor must be ready to fire");
        require(countActiveAnchored(state, planet.id()) == 0,
                "deploying extractor alone must not create visible/minable rocks");

        CelestialExtractionSystem.FireResult fired = CelestialExtractionSystem.fireCharge(state, planet.id(), "P1");
        require(fired.fired(), "extractor must fire a fracture charge at its claimed planet");
        state.celestials.update(CelestialExtractionSystem.CHARGE_SECONDS * 0.5);
        require(!CelestialExtractionSystem.released(state, planet.id()), "body must remain sealed while charge is in flight");
        require(countActiveAnchored(state, planet.id()) == 0, "rocks must remain inactive before charge impact");

        state.celestials.update(CelestialExtractionSystem.CHARGE_SECONDS * 0.51);
        require(CelestialExtractionSystem.released(state, planet.id()), "charge impact must release the target body");
        require(countActiveAnchored(state, planet.id()) == expectedRocks,
                "fracture impact must expose the complete debris field");
        require(Math.abs(totalActiveVolume(state, planet.id()) - expectedVolume(body)) < 0.001,
                "released debris field must still contain the original aggregate volume");
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
        require(moonState != null && countActiveAnchored(state, moon.id()) == moonState.resourceNodeIds.size(),
                "fractured moon must expose active debris");
        require(!CelestialExtractionSystem.released(state, planet.id()),
                "fracturing a moon must not automatically fracture its master planet");
    }

    private static void exhaustedFieldRequiresCooldownAndRefiresWithoutNodeChurn() {
        WorldSystemState state = state("extract-refire", 9104L);
        CelestialSystem.BodyView planet = planetWithMoon(state);
        Base extractor = new Base("P1:REFIRE-EXTRACTOR", "P1", CelestialExtractionSystem.EXTRACTOR_STATION_ID,
                planet.x() + planet.radius() + 145, planet.y());
        state.bases.put(extractor.id, extractor);
        state.celestials.update(0);
        require(CelestialExtractionSystem.fireCharge(state, planet.id(), "P1").fired(), "first charge must fire");
        state.celestials.update(CelestialExtractionSystem.CHARGE_SECONDS + 0.05);

        CelestialBodyState body = CelestialGameplaySystem.bodyState(state, planet.id());
        require(body != null && !body.resourceNodeIds.isEmpty(), "first fracture must create a field");
        int storedBefore = countStoredAnchored(state, planet.id());
        int resourceListBefore = state.resources.size();
        List<Integer> idsBefore = new ArrayList<>(body.resourceNodeIds);
        require(idsBefore.size() == body.profile.deposits().size() * CelestialExtractionSystem.FRAGMENTS_PER_DEPOSIT,
                "fracture cycle must use ten mineables per material");
        require(countActiveAnchored(state, planet.id()) == idsBefore.size(), "first field must be active");

        boolean exhausted = false;
        for (int id : idsBefore) {
            ResourceNode node = resourceById(state, id);
            require(node != null, "fracture node missing during depletion");
            node.deplete();
            exhausted |= CelestialExtractionSystem.onDepositDepleted(node);
        }
        require(exhausted, "last depleted rock must close the fracture cycle");
        require(!CelestialExtractionSystem.released(state, planet.id()),
                "fully depleted field must return the body to sealed state");
        require(countActiveAnchored(state, planet.id()) == 0, "depleted field must have no active rocks");
        require(countStoredAnchored(state, planet.id()) == storedBefore,
                "depletion must recycle deterministic nodes instead of deleting/reallocating them");
        require(state.resources.size() == resourceListBefore,
                "field depletion must not resize the persistent resource list");
        require(CelestialExtractionSystem.cooldownRemaining(state, planet.id()) > 0,
                "field exhaustion must start the extractor recycle delay");

        CelestialExtractionSystem.FireResult immediate = CelestialExtractionSystem.fireCharge(state, planet.id(), "P1");
        require(!immediate.fired(), "refire must be blocked during the recycle delay");
        state.celestials.update(CelestialExtractionSystem.REFIRE_COOLDOWN_SECONDS * 0.5);
        require(!CelestialExtractionSystem.fireCharge(state, planet.id(), "P1").fired(),
                "refire must remain blocked halfway through cooldown");
        state.celestials.update(CelestialExtractionSystem.REFIRE_COOLDOWN_SECONDS * 0.51);
        require(CelestialExtractionSystem.cooldownRemaining(state, planet.id()) <= 0.001,
                "cooldown must expire after the configured recycle period");

        CelestialExtractionSystem.FireResult refired = CelestialExtractionSystem.fireCharge(state, planet.id(), "P1");
        require(refired.fired(), "cooled-down field must allow another fracture charge");
        state.celestials.update(CelestialExtractionSystem.CHARGE_SECONDS + 0.05);
        require(CelestialExtractionSystem.released(state, planet.id()), "second charge must reopen the field");
        require(countActiveAnchored(state, planet.id()) == idsBefore.size(),
                "second charge must reactivate the same number of rocks");
        require(state.resources.size() == resourceListBefore,
                "refire must reactivate existing nodes without allocating another field");
        require(body.resourceNodeIds.equals(idsBefore), "refire must preserve deterministic resource ids");
        for (int id : idsBefore) {
            ResourceNode node = resourceById(state, id);
            require(node != null && node.active && Math.abs(node.amount - node.maxAmount) < 0.001,
                    "refired rock must be active and restored to full amount");
        }
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
        require(restoredBody != null && restoredBody.resourceNodeIds.size()
                        == restoredBody.profile.deposits().size() * CelestialExtractionSystem.FRAGMENTS_PER_DEPOSIT,
                "restored released body must regenerate the full deterministic fracture field");
        require(countActiveAnchored(restored, planet.id()) == restoredBody.resourceNodeIds.size(),
                "restored released field must be active");
    }

    private static double expectedVolume(CelestialBodyState body) {
        double total = 0;
        for (int slot = 0; slot < body.profile.deposits().size(); slot++) total += 1800.0 + slot * 450.0;
        return total;
    }

    private static double totalMaxVolume(WorldSystemState state, String bodyId) {
        double total = 0;
        for (ResourceNode node : state.resources) {
            if (node != null && bodyId.equals(node.celestialAnchorBodyId)) total += node.maxAmount;
        }
        return total;
    }

    private static double totalActiveVolume(WorldSystemState state, String bodyId) {
        double total = 0;
        for (ResourceNode node : state.resources) {
            if (node != null && node.active && bodyId.equals(node.celestialAnchorBodyId)) total += node.amount;
        }
        return total;
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

    private static ResourceNode resourceById(WorldSystemState state, int id) {
        for (ResourceNode node : state.resources) if (node != null && node.id == id) return node;
        return null;
    }

    private static int countActiveAnchored(WorldSystemState state, String bodyId) {
        int count = 0;
        for (ResourceNode node : state.resources) {
            if (node != null && node.active && bodyId.equals(node.celestialAnchorBodyId)) count++;
        }
        return count;
    }

    private static int countStoredAnchored(WorldSystemState state, String bodyId) {
        int count = 0;
        for (ResourceNode node : state.resources) {
            if (node != null && bodyId.equals(node.celestialAnchorBodyId)) count++;
        }
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
