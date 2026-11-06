package com.tndmadman.rts;

import java.util.Map;
import java.util.Set;

/** Fast headless regression for new-commander assets and legacy starter policy. */
public final class StartingFleetValidator {
    private StartingFleetValidator() { }

    public static void main(String[] args) {
        require(Config.parse(new String[]{"--solo"}).startingMiners == 15, "solo default is not 15");
        require(Config.parse(new String[]{"--server", "50000"}).startingMiners == 15,
                "dedicated default is not 15");
        require(Config.parse(new String[]{"--server", "50000", "--starting-miners", "30"})
                .startingMiners == 30, "server count override missing");
        for (String invalid : new String[]{"0", "51", "invalid"}) {
            try {
                Config.parse(new String[]{"--server", "50000", "--starting-miners", invalid});
                throw new AssertionError("Accepted invalid starting-miners " + invalid);
            } catch (IllegalArgumentException expected) { }
        }

        require(StartingFleetSettings.restore(null).equals(StartingFleetSettings.legacy()),
                "old archive without policy did not retain historical starter");
        require(StartingFleetSettings.restore(
                        StartingFleetSettings.newCampaign(30).capture())
                        .equals(StartingFleetSettings.newCampaign(30)),
                "starter settings round trip failed");

        for (int count : new int[]{1, 10, 15, 30, 50}) {
            PlayerRegistry.reset("SOLO", "Starter Test", 0x50BEFF);
            World world = new World("Starter Test", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true,
                    StartingFleetSettings.newCampaign(count));
            try {
                long miners = world.units.values().stream()
                        .filter(unit -> "SOLO".equals(unit.playerId)
                                && Rules.STARTING_SHIP.equals(unit.shipTypeId)).count();
                long outposts = world.bases.values().stream()
                        .filter(base -> "SOLO".equals(base.playerId)
                                && Rules.DEFAULT_BASE.equals(base.typeId)).count();
                long radars = world.bases.values().stream()
                        .filter(base -> "SOLO".equals(base.playerId)
                                && RadarTowerRules.TIER_ONE.equals(base.typeId)).count();
                require(miners == count, "expected " + count + " Prospectors, found " + miners);
                require(outposts == 1, "expected exactly one starting Outpost");
                require(radars == 1, "expected one owned Tier-1 radar");
                require(StartingFleetSettings.restore(
                        world.captureServerSaveRuntime().get("startingFleetSettings"))
                        .prospectorCount() == count, "world policy not saved");
            } finally {
                world.dispose();
            }
        }

        PlayerRegistry.reset("SOLO", "Legacy", 0x50BEFF);
        World legacy = new World("Legacy");
        try {
            require(legacy.units.values().stream().filter(
                    unit -> Rules.STARTING_SHIP.equals(unit.shipTypeId)).count() == 1,
                    "legacy constructor changed historical starter");
            require(legacy.bases.values().stream().noneMatch(
                    base -> RadarTowerRules.TIER_ONE.equals(base.typeId)),
                    "legacy world gained a radar");
        } finally {
            legacy.dispose();
        }

        System.out.println("StarChem starting fleet validator passed: 1/10/15/30/50, radar, CLI and legacy.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
