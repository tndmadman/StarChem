package com.tndmadman.rts;

import java.util.Set;

public final class TutorialControlsValidator {
    private TutorialControlsValidator() { }

    public static void main(String[] args) {
        validate();
        System.out.println("Tutorial control validation passed.");
    }

    static void validate() {
        validateObjectiveAndSectionSkipping();
        validateTrackSkippingAndRestart();
        validateWholeTutorialSkippingAndReplay();
    }

    private static void validateObjectiveAndSectionSkipping() {
        World world = new World("Tutorial Controls", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
        TutorialOverlay tutorial = new TutorialOverlay(world, true, false);
        expectStep("initial control step", tutorial, "SELECT");
        tutorial.skipStepForTest();
        expectStep("skip objective", tutorial, "HARVEST");
        tutorial.skipSectionForTest();
        expectStep("skip resource section", tutorial, "QUEUE_BUILD");
        expectEquals("next section after resources", "Production", tutorial.chapterNameForTest());
        tutorial.restartTrackForTest();
        expectStep("restart core track", tutorial, "SELECT");
        expectTrue("core active after restart", tutorial.active());
    }

    private static void validateTrackSkippingAndRestart() {
        World world = new World("Tutorial Track Controls", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
        TutorialOverlay tutorial = new TutorialOverlay(world, true, false);
        tutorial.skipTrackForTest();
        expectStep("skip core track", tutorial, "ADVANCED_READY");
        expectTrue("core complete after track skip", tutorial.coreCompletedForTest());
        expectFalse("advanced not complete after core skip", tutorial.advancedCompletedForTest());
        expectFalse("core skip hides overlay", tutorial.active());
        tutorial.toggle();
        expectStep("advanced starts after core skip", tutorial, "CATALOG");
        expectTrue("advanced active", tutorial.active());
        tutorial.skipSectionForTest();
        expectStep("skip reference section", tutorial, "QUEUE_DEPLOYER");
        tutorial.restartTrackForTest();
        expectStep("restart advanced track", tutorial, "CATALOG");
        tutorial.skipTrackForTest();
        expectStep("skip advanced track", tutorial, "COMPLETE");
        expectTrue("advanced complete after track skip", tutorial.advancedCompletedForTest());
        expectFalse("advanced skip hides overlay", tutorial.active());
    }

    private static void validateWholeTutorialSkippingAndReplay() {
        World world = new World("Tutorial Whole Controls", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
        TutorialOverlay tutorial = new TutorialOverlay(world, true, false);
        tutorial.skipAllForTest();
        expectStep("skip all tutorials", tutorial, "COMPLETE");
        expectTrue("core complete after skip all", tutorial.coreCompletedForTest());
        expectTrue("advanced complete after skip all", tutorial.advancedCompletedForTest());
        expectFalse("skip all hides overlay", tutorial.active());
        tutorial.toggle();
        expectStep("F2 replay after skip all", tutorial, "SELECT");
        expectTrue("replay active", tutorial.active());
        tutorial.skipSectionForTest();
        expectStep("skip fleet section", tutorial, "HARVEST");
        tutorial.skipSectionForTest();
        expectStep("skip resource operations section", tutorial, "QUEUE_BUILD");
        tutorial.skipSectionForTest();
        expectStep("skip production section", tutorial, "MAP");
        tutorial.skipSectionForTest();
        expectStep("skip exploration section", tutorial, "ENCOUNTER");
        tutorial.skipSectionForTest();
        expectStep("skip final core section", tutorial, "ADVANCED_READY");
        expectTrue("core complete after final section skip", tutorial.coreCompletedForTest());
        tutorial.toggle();
        tutorial.skipSectionForTest();
        expectStep("skip advanced reference section", tutorial, "QUEUE_DEPLOYER");
        tutorial.skipSectionForTest();
        expectStep("skip station expansion section", tutorial, "QUEUE_INDUSTRY");
        tutorial.skipSectionForTest();
        expectStep("skip final advanced section", tutorial, "COMPLETE");
        expectTrue("advanced complete after final section skip", tutorial.advancedCompletedForTest());
    }

    private static void expectStep(String name, TutorialOverlay tutorial, String expected) {
        String actual = tutorial.stepNameForTest();
        if (!expected.equals(actual)) throw new IllegalStateException(name + " expected " + expected + " but was " + actual + ".");
    }
    private static void expectTrue(String name, boolean actual) { if (!actual) throw new IllegalStateException("Expected true: " + name); }
    private static void expectFalse(String name, boolean actual) { if (actual) throw new IllegalStateException("Expected false: " + name); }
    private static void expectEquals(String name, Object expected, Object actual) { if (!expected.equals(actual)) throw new IllegalStateException(name + " expected " + expected + " but was " + actual + "."); }
}
