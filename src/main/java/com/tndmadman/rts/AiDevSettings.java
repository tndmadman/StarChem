package com.tndmadman.rts;

final class AiDevSettings {
    boolean pauseAi;
    boolean stepAi;
    boolean fastAi;
    boolean freezePlayerUnits;
    boolean freezeNpcCombat;
    boolean disableAttacks;
    boolean disableEconomy;
    boolean hotReloadRequested;
    boolean resetRequested;
    boolean forceSpawnRequested;
    boolean forceRaidRequested;
    boolean forceStationRequested;
    boolean forceResearchRequested;
    boolean forceCraftRequested;
    private NpcDifficultyPreset difficultyPreset = NpcDifficultyPreset.NORMAL;

    double aiDt(double dt) { return fastAi ? dt * 5.0 : dt; }
    boolean aiPaused() { return pauseAi && !stepAi; }
    void consumeStep() { if (stepAi) stepAi = false; }

    NpcDifficultyPreset difficultyPreset() { return difficultyPreset; }

    void setDifficultyPreset(NpcDifficultyPreset preset) {
        difficultyPreset = preset == null ? NpcDifficultyPreset.NORMAL : preset;
    }

    void togglePreset() {
        difficultyPreset = difficultyPreset.next();
        AiDevLog.add("DEV", "Difficulty preset: " + difficultyPreset.label);
    }

    void resetOneShotRequests() {
        stepAi = false;
        hotReloadRequested = false;
        resetRequested = false;
        forceSpawnRequested = false;
        forceRaidRequested = false;
        forceStationRequested = false;
        forceResearchRequested = false;
        forceCraftRequested = false;
    }

    void resetToDefaults() {
        pauseAi = false;
        fastAi = false;
        freezePlayerUnits = false;
        freezeNpcCombat = false;
        disableAttacks = false;
        disableEconomy = false;
        difficultyPreset = NpcDifficultyPreset.NORMAL;
        resetOneShotRequests();
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

    NpcDifficultyPreset(String label, double buildScale, double fleetScale, double raidCooldownScale) {
        this.label = label;
        this.buildScale = buildScale;
        this.fleetScale = fleetScale;
        this.raidCooldownScale = raidCooldownScale;
    }

    NpcDifficultyPreset next() {
        NpcDifficultyPreset[] presets = values();
        return presets[(ordinal() + 1) % presets.length];
    }

    boolean harassmentAllowed() { return this != NO_HARASS && this != PASSIVE; }
}
