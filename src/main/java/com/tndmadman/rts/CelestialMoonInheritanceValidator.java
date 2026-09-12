package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Regression coverage for planetary master/slave moon sovereignty. */
final class CelestialMoonInheritanceValidator {
    private CelestialMoonInheritanceValidator() { }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        planetClaimPropagatesToEveryMoon();
        planetContestAndReleasePropagateToEveryMoon();
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

    private static void planetContestAndReleasePropagateToEveryMoon() {
        WorldSystemState state = state("moon-contest", 8802L);
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
        require(master != null && master.contested && master.claimantId.isBlank(),
                "enemy master installation must contest the planet");
        for (CelestialSystem.BodyView moon : moons) {
            CelestialBodyState slave = CelestialGameplaySystem.bodyState(state, moon.id());
            require(slave != null && slave.contested && slave.claimantId.isBlank(),
                    "master contest must propagate to slave moon " + moon.id());
        }
        require(Math.abs(CelestialMoonInheritance.bonusMultiplier(
                state, "P1", CelestialBonusKind.LOGISTICS) - 1.0) < 0.0001,
                "contested planetary groups must grant no inherited moon bonuses");

        state.bases.clear();
        state.celestials.update(0);
        require(!master.contested && master.claimantId.isBlank(),
                "removing master installations must release the planet");
        for (CelestialSystem.BodyView moon : moons) {
            CelestialBodyState slave = CelestialGameplaySystem.bodyState(state, moon.id());
            require(slave != null && !slave.contested && slave.claimantId.isBlank(),
                    "released master planet must release slave moon " + moon.id());
        }
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
