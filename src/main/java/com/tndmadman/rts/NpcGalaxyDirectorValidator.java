package com.tndmadman.rts;

/**
 * Backward-compatible entry point for galaxy expansion validation.
 * Phase 8 replaced the former instant-transfer assertions with the persistent
 * expedition state-machine suite.
 */
public final class NpcGalaxyDirectorValidator {
    private NpcGalaxyDirectorValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem NPC galaxy director validation passed.");
    }

    static void validateOrThrow() {
        try {
            NpcExpeditionValidator.validateOrThrow();
        } catch (RuntimeException failure) {
            String history = String.join(" || ", AiDevLog.lines(80));
            throw new IllegalStateException(failure.getMessage()
                    + (history.isBlank() ? "" : " | recent AI events: " + history), failure);
        }
    }
}
