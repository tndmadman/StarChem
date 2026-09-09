package com.tndmadman.rts;

enum DestructionProfile {
    QUICK(0, 4, 0, 52, false, false, 2.4, 0.0, 0.0, 0.0),
    STANDARD(2, 8, 2, 100, true, false, 18.0, 1.05, 1.05, 0.0),
    CAPITAL(4, 16, 4, 160, true, true, 42.0, 1.75, 2.45, 2.15),
    MAJOR(6, 24, 6, 210, true, true, 66.0, 2.65, 3.55, 3.15),
    STATION(7, 28, 8, 240, true, true, 75.0, 3.40, 4.55, 4.05);

    final int secondaryBursts;
    final int debrisFragments;
    final int vents;
    final int particleBudget;
    final boolean persistentWreck;
    final boolean coreFlash;
    final double lifetimeSeconds;
    final double secondarySpanSeconds;
    final double wreckStartSeconds;
    final double coreTimeSeconds;

    DestructionProfile(int secondaryBursts, int debrisFragments, int vents, int particleBudget,
                       boolean persistentWreck, boolean coreFlash, double lifetimeSeconds,
                       double secondarySpanSeconds, double wreckStartSeconds, double coreTimeSeconds) {
        this.secondaryBursts = secondaryBursts;
        this.debrisFragments = debrisFragments;
        this.vents = vents;
        this.particleBudget = particleBudget;
        this.persistentWreck = persistentWreck;
        this.coreFlash = coreFlash;
        this.lifetimeSeconds = lifetimeSeconds;
        this.secondarySpanSeconds = secondarySpanSeconds;
        this.wreckStartSeconds = wreckStartSeconds;
        this.coreTimeSeconds = coreTimeSeconds;
    }

    static DestructionProfile forShipSize(ShipSize size) {
        if (size == null) return QUICK;
        return switch (size) {
            case SMALL, FRIGATE -> QUICK;
            case MEDIUM, LARGE, XL, DESTROYER, CRUISER -> STANDARD;
            case BATTLE_CRUISER, BATTLESHIP, CARRIER, DREADNOUGHT -> CAPITAL;
            case SUPERCARRIER, TITAN, MONOLITH -> MAJOR;
        };
    }
}
