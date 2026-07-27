package com.tndmadman.rts;

import java.util.prefs.Preferences;

final class TutorialPreferenceVersion {
    static final int CURRENT_VERSION = 2;
    private static final String PREF_VERSION = "firstRunTutorialStateVersion";
    private static final String[] OBSOLETE_STATE_KEYS = {
            "firstRunTutorialDisabled",
            "firstRunTutorialCompleted",
            "firstRunTutorialCoreCompleted",
            "firstRunTutorialAdvancedCompleted"
    };

    private TutorialPreferenceVersion() { }

    static void ensureCurrent() {
        ensureCurrent(Preferences.userNodeForPackage(TutorialOverlay.class));
    }

    static void ensureCurrent(Preferences preferences) {
        if (preferences == null) return;
        try {
            if (preferences.getInt(PREF_VERSION, 0) == CURRENT_VERSION) return;
            for (String key : OBSOLETE_STATE_KEYS) preferences.remove(key);
            preferences.putInt(PREF_VERSION, CURRENT_VERSION);
            preferences.flush();
        } catch (Exception ignored) {
            // Tutorial preferences are optional. Failure must never block launching the game.
        }
    }

    static boolean requiresReset(int storedVersion) {
        return storedVersion != CURRENT_VERSION;
    }

    static String versionKeyForTest() {
        return PREF_VERSION;
    }
}
