package com.tndmadman.rts;

/** Focused repeated stress coverage for organized NPC state machines. */
public final class NpcAiStressValidator {
    private static final String EXPEDITION_SEED_PROPERTY = "starchem.npcExpeditionSeed";
    private static final long FIRST_SEED = 41_000L;
    private static final int EXPEDITION_SEEDS = 64;
    private static final int CHURN_ROUNDS = 8;
    private static final int LOGGER_ROUNDS = 4;

    private NpcAiStressValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem focused NPC AI stress validation passed.");
    }

    static void validateOrThrow() {
        String previousSeed = System.getProperty(EXPEDITION_SEED_PROPERTY);
        try {
            for (long seed = FIRST_SEED; seed < FIRST_SEED + EXPEDITION_SEEDS; seed++) {
                System.setProperty(EXPEDITION_SEED_PROPERTY, Long.toString(seed));
                try {
                    NpcExpeditionValidator.validateOrThrow();
                } catch (RuntimeException ex) {
                    throw new IllegalStateException("expedition seed stress failed at seed " + seed, ex);
                }
            }
        } finally {
            if (previousSeed == null) System.clearProperty(EXPEDITION_SEED_PROPERTY);
            else System.setProperty(EXPEDITION_SEED_PROPERTY, previousSeed);
        }

        for (int round = 1; round <= CHURN_ROUNDS; round++) {
            try {
                NpcRecoveryValidator.validateOrThrow();
                NpcRuntimeResetValidator.validateOrThrow();
                NpcCrossSystemOperationsValidator.validateOrThrow();
                NpcFactionLifecycleValidator.validateOrThrow();
            } catch (RuntimeException ex) {
                throw new IllegalStateException("NPC recovery/reset churn failed in round " + round, ex);
            }
        }

        for (int round = 1; round <= LOGGER_ROUNDS; round++) {
            try {
                AiBrainLogValidator.validateOrThrow();
            } catch (RuntimeException ex) {
                throw new IllegalStateException("AI logger pressure churn failed in round " + round, ex);
            }
        }
    }
}
