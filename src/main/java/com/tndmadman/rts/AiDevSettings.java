package com.tndmadman.rts;

final class AiDevSettings {
    static boolean overlay = true;
    static boolean pathLines = true;
    static boolean pauseAi;
    static boolean stepAi;
    static boolean fastAi;
    static boolean freezePlayerUnits;
    static boolean freezeNpcCombat;
    static boolean disableAttacks;
    static boolean disableEconomy;
    static boolean hotReloadRequested;
    static boolean resetRequested;
    static boolean forceSpawnRequested;
    static boolean forceRaidRequested;
    static boolean forceStationRequested;
    static boolean forceResearchRequested;
    static boolean forceCraftRequested;

    private AiDevSettings() { }

    static double aiDt(double dt) { return fastAi ? dt * 5.0 : dt; }
    static boolean aiPaused() { return pauseAi && !stepAi; }
    static void consumeStep() { if (stepAi) stepAi = false; }

    static void togglePreset() {
        NpcDifficultyPreset.next();
        AiDevLog.add("DEV", "Difficulty preset: " + NpcDifficultyPreset.current().label);
    }
}

enum NpcDifficultyPreset {
    NORMAL("Normal", 1.0, 1.0, 1.0),
    PASSIVE("Passive", 1.5, 0.6, 1.6),
    AGGRESSIVE("Aggressive", 0.75, 1.25, 0.6),
    ECONOMIC("Economic", 0.7, 0.85, 1.2),
    RAIDER_HEAVY("Raider-heavy", 0.65, 1.45, 0.55),
    NO_HARASS("No harassment", 1.0, 1.0, 2.0),
    FULL_WAR("Full war", 0.45, 1.8, 0.25);

    final String label;
    final double buildScale;
    final double fleetScale;
    final double raidCooldownScale;
    private static int index;

    NpcDifficultyPreset(String label, double buildScale, double fleetScale, double raidCooldownScale) {
        this.label = label;
        this.buildScale = buildScale;
        this.fleetScale = fleetScale;
        this.raidCooldownScale = raidCooldownScale;
    }

    static NpcDifficultyPreset current() { return values()[index]; }
    static void next() { index = (index + 1) % values().length; }
    static boolean harassmentAllowed() { return current() != NO_HARASS && current() != PASSIVE; }
}
