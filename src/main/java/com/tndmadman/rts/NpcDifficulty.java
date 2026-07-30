package com.tndmadman.rts;

import java.util.Locale;

enum NpcDifficulty {
    RELAXED("relaxed", "Relaxed", 1.35, 0.80),
    NORMAL("normal", "Normal", 1.0, 1.0),
    HARD("hard", "Hard", 0.75, 1.25),
    BRUTAL("brutal", "Brutal", 0.55, 1.55);

    private final String id;
    private final String label;
    private final double timingMultiplier;
    private final double forceMultiplier;

    NpcDifficulty(String id, String label, double timingMultiplier, double forceMultiplier) {
        this.id = id;
        this.label = label;
        this.timingMultiplier = timingMultiplier;
        this.forceMultiplier = forceMultiplier;
    }

    String id() { return id; }
    String label() { return label; }
    double timingMultiplier() { return timingMultiplier; }
    double forceMultiplier() { return forceMultiplier; }

    static NpcDifficulty parse(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (NpcDifficulty difficulty : values()) {
            if (difficulty.id.equals(clean) || difficulty.name().equalsIgnoreCase(clean)) return difficulty;
        }
        throw new IllegalArgumentException("Unknown NPC difficulty: " + value
                + ". Expected relaxed, normal, hard, or brutal.");
    }

    @Override public String toString() { return label; }
}
