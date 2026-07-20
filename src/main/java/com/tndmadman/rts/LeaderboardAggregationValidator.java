package com.tndmadman.rts;

import java.util.List;
import java.util.Set;

public final class LeaderboardAggregationValidator {
    private LeaderboardAggregationValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem leaderboard aggregation validation passed.");
    }

    static void validateOrThrow() {
        validateThrottleAndInitialSynchronization();
        validateAuthoritativeScoreChanges();
    }

    private static void validateThrottleAndInitialSynchronization() {
        Fixture fixture = fixture("Leaderboard Throttle");
        GlobalLeaderboard.forceNextAuthoritativeSend(fixture.world);
        List<LeaderboardEntry> initial = aggregate(fixture, 1_000);
        require(GlobalLeaderboard.authoritativeAggregationCount(fixture.world) == 1,
                "initial leaderboard aggregation count was incorrect");
        String initialMessage = GlobalLeaderboard.encode(initial);
        require(initialMessage != null && initialMessage.startsWith("LEADER|"),
                "initial synchronization did not produce a leaderboard payload");

        fixture.world.activateSystem(fixture.firstSystem);
        fixture.p1Unit.hp -= 50;
        fixture.world.saveActiveSystem();
        List<LeaderboardEntry> throttled = aggregate(fixture, 1_500);
        require(throttled == initial,
                "leaderboard cache did not reuse its immutable snapshot inside the refresh interval");
        require(GlobalLeaderboard.authoritativeAggregationCount(fixture.world) == 1,
                "throttled leaderboard call still scanned the galaxy");
        require(GlobalLeaderboard.encode(throttled) == null,
                "unchanged cached leaderboard was queued again");

        List<LeaderboardEntry> changed = aggregate(fixture, 2_000);
        require(GlobalLeaderboard.authoritativeAggregationCount(fixture.world) == 2,
                "leaderboard did not refresh after the maximum delay");
        String changedMessage = GlobalLeaderboard.encode(changed);
        require(changedMessage != null && !changedMessage.equals(initialMessage),
                "changed leaderboard did not produce a new payload");

        List<LeaderboardEntry> unchanged = aggregate(fixture, 3_000);
        require(GlobalLeaderboard.authoritativeAggregationCount(fixture.world) == 3,
                "periodic leaderboard verification did not run");
        require(unchanged == changed,
                "unchanged leaderboard replaced its cached snapshot");
        require(GlobalLeaderboard.encode(unchanged) == null,
                "unchanged leaderboard was re-encoded or queued");

        GlobalLeaderboard.forceNextAuthoritativeSend(fixture.world);
        List<LeaderboardEntry> forced = aggregate(fixture, 3_100);
        require(GlobalLeaderboard.authoritativeAggregationCount(fixture.world) == 3,
                "initial synchronization bypassed the leaderboard throttle");
        require(forced == unchanged,
                "initial synchronization replaced the cached leaderboard snapshot");
        require(GlobalLeaderboard.encode(forced) != null,
                "initial synchronization did not force the cached payload to be sent");
    }

    private static void validateAuthoritativeScoreChanges() {
        Fixture fixture = fixture("Leaderboard Correctness");
        long now = 1_000;

        List<LeaderboardEntry> entries = aggregate(fixture, now);
        consume(entries);
        require(fixture.firstSystem.equals(fixture.world.activeSystemId()),
                "leaderboard aggregation did not restore the active system");
        requireEntry(entries, "P1", 1, 1,
                score(fixture.p1Unit.hp, fixture.p1Base.hp, 1, 1), "asset creation");
        requireEntry(entries, "P2", 1, 0,
                score(fixture.p2Unit.hp, 0, 1, 0), "cross-system creation");

        fixture.world.activateSystem(fixture.firstSystem);
        fixture.p1Unit.hp -= 75;
        fixture.p1Base.hp -= 125;
        fixture.world.saveActiveSystem();
        entries = aggregate(fixture, now += 1_000);
        consume(entries);
        requireEntry(entries, "P1", 1, 1,
                score(fixture.p1Unit.hp, fixture.p1Base.hp, 1, 1), "damage");

        fixture.p1Unit.hp = fixture.p1Unit.type().maxHp;
        fixture.p1Base.hp = fixture.p1Base.type().maxHp;
        fixture.world.saveActiveSystem();
        entries = aggregate(fixture, now += 1_000);
        consume(entries);
        requireEntry(entries, "P1", 1, 1,
                score(fixture.p1Unit.hp, fixture.p1Base.hp, 1, 1), "healing");

        fixture.p1Unit.hp = 0;
        fixture.p1Base.hp = 0;
        fixture.world.saveActiveSystem();
        entries = aggregate(fixture, now += 1_000);
        consume(entries);
        require(find(entries, "P1") == null,
                "destroyed assets remained on the leaderboard");

        fixture.world.units.remove(fixture.p1Unit.key());
        fixture.world.bases.remove(fixture.p1Base.id);
        Unit p3Unit = new Unit("P3", 1, Rules.STARTING_SHIP, 400, 400);
        Base p3Base = new Base("P3:B1", "P3", Rules.DEFAULT_BASE, 500, 500);
        fixture.world.units.put(p3Unit.key(), p3Unit);
        fixture.world.bases.put(p3Base.id, p3Base);
        fixture.world.saveActiveSystem();
        entries = aggregate(fixture, now += 1_000);
        consume(entries);
        require(find(entries, "P1") == null,
                "ownership change retained the previous owner");
        requireEntry(entries, "P3", 1, 1, score(p3Unit.hp, p3Base.hp, 1, 1), "ownership change");

        fixture.world.activateSystem(fixture.secondSystem);
        Unit transferred = fixture.world.units.remove(fixture.p2Unit.key());
        require(transferred != null, "system transfer source unit was missing");
        fixture.world.saveActiveSystem();
        fixture.world.activateSystem(fixture.firstSystem);
        fixture.world.units.put(transferred.key(), transferred);
        fixture.world.saveActiveSystem();
        entries = aggregate(fixture, now += 1_000);
        consume(entries);
        requireEntry(entries, "P2", 1, 0, score(transferred.hp, 0, 1, 0), "system transfer");
        require(fixture.firstSystem.equals(fixture.world.activeSystemId()),
                "aggregation after system transfer did not restore the active system");
    }

    private static Fixture fixture(String name) {
        PlayerRegistry.reset("WAIT", name, 0x50BEFF);
        World world = new World(name, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        String firstSystem = world.activeSystemId();
        String secondSystem = world.authoritativeGalaxyMapSnapshot().systems().stream()
                .map(GalaxyMapSystem::id)
                .filter(id -> id != null && !id.isBlank() && !id.equals(firstSystem))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("leaderboard validator requires two galaxy systems"));

        world.activateSystem(firstSystem);
        Unit p1Unit = new Unit("P1", 1, Rules.STARTING_SHIP, 100, 100);
        Base p1Base = new Base("P1:B1", "P1", Rules.DEFAULT_BASE, 200, 200);
        world.units.put(p1Unit.key(), p1Unit);
        world.bases.put(p1Base.id, p1Base);
        world.saveActiveSystem();

        world.activateSystem(secondSystem);
        Unit p2Unit = new Unit("P2", 1, Rules.STARTING_SHIP, 300, 300);
        world.units.put(p2Unit.key(), p2Unit);
        world.saveActiveSystem();
        world.activateSystem(firstSystem);

        return new Fixture(world, firstSystem, secondSystem, p1Unit, p1Base, p2Unit);
    }

    private static List<LeaderboardEntry> aggregate(Fixture fixture, long now) {
        return GlobalLeaderboard.aggregate(fixture.world,
                new String[]{fixture.firstSystem, fixture.secondSystem}, now);
    }

    private static void consume(List<LeaderboardEntry> entries) {
        GlobalLeaderboard.encode(entries);
    }

    private static int score(double unitHp, double baseHp, int units, int bases) {
        return (int)Math.round(unitHp + baseHp + units * 100.0 + bases * 1_000.0);
    }

    private static void requireEntry(List<LeaderboardEntry> entries, String playerId, int units, int bases,
                                     int expectedScore, String context) {
        LeaderboardEntry entry = find(entries, playerId);
        require(entry != null, context + ": missing player " + playerId);
        require(entry.units() == units, context + ": incorrect unit count");
        require(entry.bases() == bases, context + ": incorrect base count");
        require(entry.score() == expectedScore, context + ": incorrect score");
    }

    private static LeaderboardEntry find(List<LeaderboardEntry> entries, String playerId) {
        for (LeaderboardEntry entry : entries) {
            if (entry != null && playerId.equals(entry.playerId())) return entry;
        }
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Fixture(World world, String firstSystem, String secondSystem,
                           Unit p1Unit, Base p1Base, Unit p2Unit) { }
}
