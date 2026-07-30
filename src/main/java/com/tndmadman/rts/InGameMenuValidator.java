package com.tndmadman.rts;

import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.List;

public final class InGameMenuValidator {
    private InGameMenuValidator() { }

    public static void main(String[] args) {
        validate();
        System.out.println("Exact game menu, settings, control binding, and audio validation passed.");
    }

    static void validate() {
        GameMenuOverlay soloMenu = new GameMenuOverlay(true);
        expectEquals("feature menu panel width", 460, GameMenuOverlay.panelWidthForTest());
        expectEquals("feature menu button width", 300, GameMenuOverlay.buttonWidthForTest());
        expectEquals("feature menu button height", 48, GameMenuOverlay.buttonHeightForTest());
        expectEquals("feature menu labels", List.of(
                "Return to Game",
                "Settings",
                "Diplomacy",
                "Start / Resume Tutorial",
                "Return to Main Menu",
                "Quit Game"), soloMenu.labelsForTest());

        GameMenuOverlay multiplayerMenu = new GameMenuOverlay(false);
        expectEquals("multiplayer tutorial label", "Tutorial - Solo Only",
                multiplayerMenu.labelsForTest().get(3));
        expectEquals("settings panel width", 980, SettingsPanel.panelWidthForTest());
        expectEquals("settings panel height", 680, SettingsPanel.panelHeightForTest());

        Rectangle slider = new Rectangle(100, 20, 400, 50);
        expectEquals("settings slider left", 0, SettingsPanel.sliderValue(100, slider));
        expectEquals("settings slider midpoint", 50, SettingsPanel.sliderValue(300, slider));
        expectEquals("settings slider right", 100, SettingsPanel.sliderValue(500, slider));
        expectEquals("settings slider low clamp", 0, SettingsPanel.sliderValue(-100, slider));
        expectEquals("settings slider high clamp", 100, SettingsPanel.sliderValue(900, slider));

        expectTrue("Solo menu pauses simulation", InGameMenuOverlay.pausesSimulation(true));
        expectFalse("multiplayer menu does not pause simulation", InGameMenuOverlay.pausesSimulation(false));

        expectClose("full mixer gain", 1.0, ProceduralAudio.effectiveGain(100, false));
        expectClose("half mixer gain", 0.5, ProceduralAudio.effectiveGain(50, false));
        expectClose("muted mixer gain", 0.0, ProceduralAudio.effectiveGain(100, true));

        int previousVolume = ProceduralAudio.volumePercent();
        boolean previousMuted = ProceduralAudio.muted();
        try {
            GameSettings settings = GameSettings.forTest();
            settings.setMasterVolume(50);
            settings.setEffectsVolume(80);
            expectEquals("combined settings volume", 40, settings.effectiveEffectsVolumeForTest());
            expectEquals("combined volume reaches mixer", 40, ProceduralAudio.volumePercent());
            settings.setEffectsVolume(20);
            expectEquals("effects update reaches mixer", 10, ProceduralAudio.volumePercent());
            settings.setMasterVolume(500);
            settings.setEffectsVolume(-20);
            expectEquals("volume clamp", 0, settings.effectiveEffectsVolumeForTest());

            expectEquals("rebind formation", GameSettings.RebindResult.APPLIED,
                    settings.rebindSwap("formation", KeyEvent.VK_Q, false));
            expectEquals("rebound formation text", "Q", settings.bindingText("formation"));
            expectEquals("duplicate rebind blocked", GameSettings.RebindResult.BLOCKED,
                    settings.rebindSwap("guard", KeyEvent.VK_P, false));
            expectEquals("pause menu fixed", GameSettings.RebindResult.BLOCKED,
                    settings.rebindSwap("pause_menu", KeyEvent.VK_BACK_SPACE, false));
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
