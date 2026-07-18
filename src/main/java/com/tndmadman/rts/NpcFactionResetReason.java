package com.tndmadman.rts;

/** Defines how persistent organized-faction commitments are settled during reset. */
enum NpcFactionResetReason {
    DEFEATED(false),
    SPAWN_PREP(false),
    DISABLED(true),
    DEV_RESET(true),
    WORLD_REBUILD(false),
    SEED_CHANGE(false);

    private final boolean refundUnlaunched;

    NpcFactionResetReason(boolean refundUnlaunched) {
        this.refundUnlaunched = refundUnlaunched;
    }

    boolean refundsUnlaunched() {
        return refundUnlaunched;
    }

    boolean discardsWorldState() {
        return this == WORLD_REBUILD || this == SEED_CHANGE;
    }
}
