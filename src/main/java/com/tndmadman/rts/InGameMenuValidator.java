package com.tndmadman.rts;

public final class InGameMenuValidator {
    private InGameMenuValidator() { }

    public static void main(String[] args) {
        validate();
        System.out.println("In-game menu and audio control validation passed.");
    }

    static void validate() {
        expectEquals("slider left edge", 0,
                InGameMenuOverlay.volumePercentAt(106, 100, 280));
        expectEquals("slider midpoint", 50,
                InGameMenuOverlay.volumePercentAt(240, 100, 280));
        expectEquals("slider right edge", 100,
                InGameMenuOverlay.volumePercentAt(374, 100, 280));
        expectEquals("slider clamps below", 0,
                InGameMenuOverlay.volumePercentAt(-200, 100, 280));
        expectEquals("slider clamps above", 100,
                InGameMenuOverlay.volumePercentAt(900, 100, 280));

        expectTrue("Solo menu pauses simulation", InGameMenuOverlay.pausesSimulation(true));
        expectFalse("multiplayer menu does not pause simulation", InGameMenuOverlay.pausesSimulation(false));

        expectClose("full mixer gain", 1.0, ProceduralAudio.effectiveGain(100, false));
        expectClose("half mixer gain", 0.5, ProceduralAudio.effectiveGain(50, false));
        expectClose("zero mixer gain", 0.0, ProceduralAudio.effectiveGain(0, false));
        expectClose("muted mixer gain", 0.0, ProceduralAudio.effectiveGain(100, true));
        expectClose("volume clamps high", 1.0, ProceduralAudio.effectiveGain(500, false));
        expectClose("volume clamps low", 0.0, ProceduralAudio.effectiveGain(-10, false));

        int previousVolume = ProceduralAudio.volumePercent();
        boolean previousMuted = ProceduralAudio.muted();
        try {
            expectEquals("volume setter", 37, ProceduralAudio.setVolumePercent(37));
            expectEquals("volume state", 37, ProceduralAudio.volumePercent());
            expectEquals("volume setter clamps", 100, ProceduralAudio.setVolumePercent(120));
            expectTrue("mute setter", ProceduralAudio.setMuted(true));
            expectTrue("mute state", ProceduralAudio.muted());
            expectFalse("unmute setter", ProceduralAudio.setMuted(false));
            expectFalse("unmute state", ProceduralAudio.muted());
        } finally {
            ProceduralAudio.setVolumePercent(previousVolume);
            ProceduralAudio.setMuted(previousMuted);
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

    private static void expectClose(String name, double expected, double actual) {
        if (Math.abs(expected - actual) > 0.000001) {
            throw new IllegalStateException(name + " expected " + expected + " but was " + actual + ".");
        }
    }
}
