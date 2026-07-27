package com.tndmadman.rts;

import java.util.prefs.Preferences;

public final class TutorialPreferenceVersionValidator {
    private TutorialPreferenceVersionValidator() { }

    public static void main(String[] args) {
        validate();
        System.out.println("Tutorial preference version validation passed.");
    }

    static void validate() {
        expectTrue("older tutorial state requires reset",
                TutorialPreferenceVersion.requiresReset(TutorialPreferenceVersion.CURRENT_VERSION - 1));
        expectFalse("current tutorial state is retained",
                TutorialPreferenceVersion.requiresReset(TutorialPreferenceVersion.CURRENT_VERSION));

        Preferences preferences = Preferences.userRoot().node(
                "/com/tndmadman/rts/tutorial-version-validator-" + System.nanoTime());
        try {
            preferences.putBoolean("firstRunTutorialDisabled", true);
            preferences.putBoolean("firstRunTutorialCompleted", true);
            preferences.putBoolean("firstRunTutorialCoreCompleted", true);
            preferences.putBoolean("firstRunTutorialAdvancedCompleted", true);

            TutorialPreferenceVersion.ensureCurrent(preferences);

            expectFalse("obsolete disabled flag cleared",
                    preferences.getBoolean("firstRunTutorialDisabled", false));
            expectFalse("obsolete legacy completion cleared",
                    preferences.getBoolean("firstRunTutorialCompleted", false));
            expectFalse("obsolete core completion cleared",
                    preferences.getBoolean("firstRunTutorialCoreCompleted", false));
            expectFalse("obsolete advanced completion cleared",
                    preferences.getBoolean("firstRunTutorialAdvancedCompleted", false));
            expectEquals("current version recorded", TutorialPreferenceVersion.CURRENT_VERSION,
                    preferences.getInt(TutorialPreferenceVersion.versionKeyForTest(), 0));

            preferences.putBoolean("firstRunTutorialDisabled", true);
            TutorialPreferenceVersion.ensureCurrent(preferences);
            expectTrue("current-version pause state preserved",
                    preferences.getBoolean("firstRunTutorialDisabled", false));
        } catch (Exception ex) {
            throw new IllegalStateException("Tutorial preference version validation failed.", ex);
        } finally {
            try {
                preferences.removeNode();
                preferences.flush();
            } catch (Exception ignored) { }
        }
    }

    private static void expectTrue(String name, boolean actual) {
        if (!actual) throw new IllegalStateException("Expected true: " + name);
    }

    private static void expectFalse(String name, boolean actual) {
        if (actual) throw new IllegalStateException("Expected false: " + name);
    }

    private static void expectEquals(String name, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(name + " expected " + expected + " but was " + actual + ".");
        }
    }
}
