package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Regression coverage for planetary master/slave moon sovereignty and anchor access. */
final class CelestialMoonInheritanceValidator {
    private CelestialMoonInheritanceValidator() { }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        planetClaimPropagatesToEveryMoon();
        hostileStationsCannotAnchorAfterClaimStarts();
        alliedStationsCanSharePlanetaryAnchors();
        objectiveHelpExplainsStationRequirements();
        System.out.println("Celestial moon inheritance validation passed.");
    }

    private static void planetClaimPropagatesToEveryMoon() {
        WorldSystemState state = state("moon-inheritance", 8801L);
        CelestialSystem.BodyView planet = planetWithMoons(state);
        List<CelestialSystem.BodyView> moons = moonsOf(state, planet.id());
        require(!moons.isEmpty(), "fixture requires a planet with at least one moon");

        Base station = new Base("P1:PLANET-HUB", "P1", "outpost", planet.x(), planet.y());
        state.bases.put(station.id, station);
        state.celestials.update(0);

        CelestialBodyState master = CelestialGameplaySystem.bodyState(state, planet.id());
        require(master != null && "P1".equals(master.claimantId) && !master.contested,
                "planetary station must claim the master planet");
        require(planet.id().equals(station.celestialAnchorBodyId),
                "master station must remain anchored to the planet rather than a moon");

        for (CelestialSystem.BodyView moon : moons) {
            CelestialBodyState slave = CelestialGameplaySystem.bodyState(state, moon.id());
            require(slave != null && "P1".equals(slave.claimantId) && !slave.contested,
                    "claimed master planet must automatically claim slave moon " + moon.id());
            require(CelestialGameplaySystem.objectiveStatus(
                    state, moon.id(), "P1", CelestialObjectiveType.CLAIM).complete(),
                    "slave moon claim objective must complete with its master planet");
        }

        double planetOnly = CelestialGameplaySystem.bonusMultiplier(
                state, "P1", CelestialBonusKind.LOGISTICS);
        double planetAndMoons = CelestialMoonInheritance.bonusMultiplier(
                state, "P1", CelestialBonusKind.LOGISTICS);
        require(planetAndMoons > planetOnly + 0.0001,
                "slave moons must contribute their own logistics bonuses on top of the master planet");

        double expectedBonus = master.profile.bonuses().getOrDefault(CelestialBonusKind.LOGISTICS, 0.0);
        for (CelestialSystem.BodyView moon : moons) {
            CelestialBodyState slave = CelestialGameplaySystem.bodyState(state, moon.id());
            expectedBonus += slave.profile.bonuses().getOrDefault(CelestialBonusKind.LOGISTICS, 0.0);
        }
        double expectedMultiplier = 1.0 + Math.min(0.35, expectedBonus);
        require(Math.abs(planetAndMoons - expectedMultiplier) < 0.0001,
                "every slave moon must add its own authored boost to the planetary system value");

        state.celestials.update(37.0);
        double masterHold = master.holdSecondsByPlayer.getOrDefault("P1", 0.0);
        require(masterHold > 0, "claimed master planet must accumulate hold progress");
        for (CelestialSystem.BodyView moon : moons) {
            CelestialBodyState slave = CelestialGameplaySystem.bodyState(state, moon.id());
            require(Math.abs(slave.holdSecondsByPlayer.getOrDefault("P1", 0.0) - masterHold) < 0.0001,
                    "slave moon hold progress must mirror the master planet");
        }
    }

    private static void hostileStationsCannotAnchorAfterClaimStarts() {
        WorldSystemState state = state("moon-anchor-lock", 8802L);
        CelestialSystem.BodyView planet = planetWithMoons(state);
        List<CelestialSystem.BodyView> moons = moonsOf(state, planet.id());

        Base local = new Base("P1:MASTER", "P1", "outpost", planet.x(), planet.y());
        state.bases.put(local.id, local);
        state.celestials.update(0);

        CelestialSystem.BodyView moved = state.celestials.bodyView(planet.id());
        Base enemy = new Base("P2:MASTER", "P2", "outpost", moved.x(), moved.y());
        state.bases.put(enemy.id, enemy);
        state.celestials.update(0);

        CelestialBodyState master = CelestialGameplaySystem.bodyState(state, planet.id());
        require(master != null && !master.contested && "P1".equals(master.claimantId),
                "hostile station must not contest a planet whose claim has already started");
        require(enemy.celestialAnchorBodyId == null || enemy.celestialAnchorBodyId.isBlank(),
                "hostile station must be rejected instead of anchoring to the claimed planet");
        for (CelestialSystem.BodyView moon : moons) {
            CelestialBodyState slave = CelestialGameplaySystem.bodyState(state, moon.id());
            require(slave != null && !slave.contested && "P1".equals(slave.claimantId),
                    "rejected hostile anchor must not disturb inherited moon sovereignty");
        }

        CelestialSystem.BodyView moon = moons.get(0);
        CelestialSystem.BodyView movedMoon = state.celestials.bodyView(moon.id());
        Base moonEnemy = new Base("P3:MOON", "P3", "outpost", movedMoon.x(), movedMoon.y());
        state.bases.put(moonEnemy.id, moonEnemy);
        state.celestials.update(0);
        require(moonEnemy.celestialAnchorBodyId == null || moonEnemy.celestialAnchorBodyId.isBlank(),
                "hostile station must also be rejected from slave moons of a claimed planet");

        state.bases.clear();
        state.celestials.update(0);
        require(!master.contested && master.claimantId.isBlank(),
                "removing master installations must release the planet");
        for (CelestialSystem.BodyView child : moons) {
            CelestialBodyState slave = CelestialGameplaySystem.bodyState(state, child.id());
            require(slave != null && !slave.contested && slave.claimantId.isBlank(),
                    "released master planet must release slave moon " + child.id());
        }
    }

    private static void alliedStationsCanSharePlanetaryAnchors() {
        World diplomacyWorld = new World("Celestial Alliance Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(diplomacyWorld);
        DiplomacySystem.setRelationship(diplomacyWorld, "P1", "P2", DiplomacySystem.Relationship.ALLIED);

        WorldSystemState state = state("moon-allied-anchor", 8803L);
        CelestialSystem.BodyView planet = planetWithMoons(state);
        Base owner = new Base("P1:MASTER", "P1", "outpost", planet.x(), planet.y());
        state.bases.put(owner.id, owner);
        state.celestials.update(0);

        CelestialSystem.BodyView moved = state.celestials.bodyView(planet.id());
        Base ally = new Base("P2:ALLY", "P2", "radar_picket", moved.x(), moved.y());
        state.bases.put(ally.id, ally);
        state.celestials.update(0);

        CelestialBodyState master = CelestialGameplaySystem.bodyState(state, planet.id());
        require(planet.id().equals(ally.celestialAnchorBodyId),
                "allied station must be allowed to share the planetary anchor");
        require(master != null && !master.contested && "P1".equals(master.claimantId),
                "allied anchor must not contest or steal the existing planetary claim");
        require(master.installations.contains(CelestialInstallationType.SENSOR_ARRAY),
                "allied anchored station must participate in friendly planetary installations");
    }

    private static void objectiveHelpExplainsStationRequirements() {
        WorldSystemState state = state("moon-objective-help", 8804L);
        CelestialSystem.BodyView planet = planetWithMoons(state);
        CelestialSystem.BodyView moon = moonsOf(state, planet.id()).get(0);

        String scan = CelestialObjectiveHelp.requirement(state, planet.id(), CelestialObjectiveType.SCAN);
        String claim = CelestialObjectiveHelp.requirement(state, planet.id(), CelestialObjectiveType.CLAIM);
        String hold = CelestialObjectiveHelp.requirement(state, planet.id(), CelestialObjectiveType.HOLD);
        String extract = CelestialObjectiveHelp.requirement(state, planet.id(), CelestialObjectiveType.EXTRACT);
        String moonClaim = CelestialObjectiveHelp.requirement(state, moon.id(), CelestialObjectiveType.CLAIM);

        require(scan.contains("Station needed: none") && scan.contains("Sensor Array"),
                "SCAN help must explain that no specific station is required");
        require(claim.contains("any friendly orbital station") && claim.contains("hostile and neutral"),
                "CLAIM help must explain friendly-anchor ownership lock");
        require(hold.contains("friendly orbital station") && hold.contains("120"),
                "HOLD help must state the station and hold-duration requirement");
        require(extract.contains("Manufacturing/Extractor") && extract.contains("500"),
                "EXTRACT help must explain mining progress and extractor bonus requirement");
        require(moonClaim.contains(planet.name()) && moonClaim.contains("no separate moon claim station"),
                "moon CLAIM help must point players to the master planet");
    }

    private static WorldSystemState state(String id, long seed) {
        StarSystemDefinition definition = StarSystems.defaultSystem();
        CelestialSystem celestials = new CelestialSystem(definition, new Random(seed));
        WorldSystemState state = new WorldSystemState(id, definition, celestials);
        celestials.update(0);
        return state;
    }

    private static CelestialSystem.BodyView planetWithMoons(WorldSystemState state) {
        for (CelestialSystem.BodyView body : state.celestials.bodyViews()) {
            if (body.visualClass() == CelestialVisualClass.STAR || body.moon()) continue;
            if (!moonsOf(state, body.id()).isEmpty()) return body;
        }
        throw new IllegalStateException("fixture requires a planet with moons");
    }

    private static List<CelestialSystem.BodyView> moonsOf(WorldSystemState state, String planetId) {
        List<CelestialSystem.BodyView> out = new ArrayList<>();
        for (CelestialSystem.BodyView body : state.celestials.bodyViews()) {
            if (!body.moon()) continue;
            if (planetId.equals(CelestialMoonInheritance.masterPlanetId(state, body.id()))) out.add(body);
        }
        return out;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
