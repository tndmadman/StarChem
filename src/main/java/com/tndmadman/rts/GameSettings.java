package com.tndmadman.rts;

import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Persistent player-facing display, audio, and control settings. */
public final class GameSettings {
    public enum RebindResult { APPLIED, SWAPPED, BLOCKED }

    public static final class Binding {
        private final String group;
        private final String label;
        private final String actionId;
        private int keyCode;
        private boolean ctrlRequired;
        private final boolean editable;

        private Binding(String group, String label, String actionId,
                        int keyCode, boolean ctrlRequired, boolean editable) {
            this.group = group;
            this.label = label;
            this.actionId = actionId;
            this.keyCode = keyCode;
            this.ctrlRequired = ctrlRequired;
            this.editable = editable;
        }

        public String group() { return group; }
        public String label() { return label; }
        public String actionId() { return actionId; }
        public int keyCode() { return keyCode; }
        public boolean ctrlRequired() { return ctrlRequired; }
        public boolean editable() { return editable; }

        private void set(int keyCode, boolean ctrlRequired) {
            this.keyCode = keyCode;
            this.ctrlRequired = ctrlRequired;
        }

        public String displayText() {
            return (ctrlRequired ? "Ctrl+" : "") + KeyEvent.getKeyText(keyCode);
        }

        private boolean matches(KeyEvent event) {
            return event != null
                    && keyCode == event.getKeyCode()
                    && ctrlRequired == event.isControlDown()
                    && !event.isAltDown()
                    && !event.isMetaDown();
        }
    }

    private static final Path CONFIG_PATH = Paths.get(
            System.getProperty("user.home", "."), "starchem-settings.properties");

    private static final Dimension[] RESOLUTIONS = {
            new Dimension(0, 0),
            new Dimension(1920, 1080),
            new Dimension(1366, 768),
            new Dimension(1440, 900),
            new Dimension(2560, 1440)
    };

    private final LinkedHashMap<String, Binding> bindings = new LinkedHashMap<>();
    private final boolean persistent;

    private boolean fullscreen;
    private boolean showFps;
    private int resolutionIndex;
    private int masterVolume = 100;
    private int musicVolume = 100;
    private int effectsVolume = 80;
    private boolean tutorialHints = true;
    private boolean pauseOnFocusLost = true;
    private boolean edgeScrolling = true;
    private boolean confirmDangerousActions = true;

    private GameSettings(boolean persistent) {
        this.persistent = persistent;
        seedDefaults();
    }

    public static GameSettings load() {
        GameSettings settings = new GameSettings(true);
        settings.read();
        settings.applyAudioSettings();
        return settings;
    }

    static GameSettings forTest() {
        GameSettings settings = new GameSettings(false);
        settings.applyAudioSettings();
        return settings;
    }

    public void save() {
        if (!persistent) return;
        Properties props = new Properties();
        props.setProperty("display.fullscreen", Boolean.toString(fullscreen));
        props.setProperty("display.showFps", Boolean.toString(showFps));
        props.setProperty("display.resolutionIndex", Integer.toString(resolutionIndex));
        props.setProperty("audio.masterVolume", Integer.toString(masterVolume));
        props.setProperty("audio.musicVolume", Integer.toString(musicVolume));
        props.setProperty("audio.effectsVolume", Integer.toString(effectsVolume));
        props.setProperty("gameplay.tutorialHints", Boolean.toString(tutorialHints));
        props.setProperty("gameplay.pauseOnFocusLost", Boolean.toString(pauseOnFocusLost));
        props.setProperty("gameplay.edgeScrolling", Boolean.toString(edgeScrolling));
        props.setProperty("gameplay.confirmDangerousActions", Boolean.toString(confirmDangerousActions));
        for (Binding binding : bindings.values()) {
            props.setProperty("bind." + binding.actionId + ".keyCode", Integer.toString(binding.keyCode));
            props.setProperty("bind." + binding.actionId + ".ctrlRequired", Boolean.toString(binding.ctrlRequired));
        }
        try {
            if (CONFIG_PATH.getParent() != null) Files.createDirectories(CONFIG_PATH.getParent());
            try (OutputStream output = Files.newOutputStream(CONFIG_PATH)) {
                props.store(output, "StarChem settings");
            }
        } catch (IOException ex) {
            System.err.println("Could not save StarChem settings: " + ex.getMessage());
        }
    }

    public List<Binding> bindings() {
        return Collections.unmodifiableList(new ArrayList<>(bindings.values()));
    }

    public Binding binding(String actionId) { return bindings.get(actionId); }

    public boolean matches(String actionId, KeyEvent event) {
        Binding binding = bindings.get(actionId);
        return binding != null && binding.matches(event);
    }

    public String bindingText(String actionId) {
        Binding binding = bindings.get(actionId);
        return binding == null ? "" : binding.displayText();
    }

    public RebindResult rebindSwap(String actionId, int keyCode, boolean ctrlRequired) {
        Binding target = bindings.get(actionId);
        if (target == null || !target.editable || keyCode == KeyEvent.VK_UNDEFINED) {
            return RebindResult.BLOCKED;
        }
        Binding conflict = findConflict(actionId, keyCode, ctrlRequired);
        if (conflict != null) return RebindResult.BLOCKED;
        target.set(keyCode, ctrlRequired);
        save();
        return RebindResult.APPLIED;
    }

    public void resetDefaults() {
        bindings.clear();
        seedDefaults();
        fullscreen = false;
        showFps = false;
        resolutionIndex = 0;
        masterVolume = 100;
        musicVolume = 100;
        effectsVolume = 80;
        tutorialHints = true;
        pauseOnFocusLost = true;
        edgeScrolling = true;
        confirmDangerousActions = true;
        applyAudioSettings();
        save();
    }

    public boolean isFullscreen() { return fullscreen; }
    public void setFullscreen(boolean value) { fullscreen = value; save(); }
    public boolean isShowFps() { return showFps; }
    public void setShowFps(boolean value) { showFps = value; save(); }
    public int resolutionIndex() { return resolutionIndex; }

    public void setResolutionIndex(int index) {
        resolutionIndex = clamp(index, 0, RESOLUTIONS.length - 1);
        save();
    }

    public Dimension selectedResolution() { return new Dimension(RESOLUTIONS[resolutionIndex]); }

    public String resolutionLabel() {
        Dimension dimension = RESOLUTIONS[resolutionIndex];
        return dimension.width == 0 ? "Native" : dimension.width + " × " + dimension.height;
    }

    public String[] resolutionLabels() {
        String[] labels = new String[RESOLUTIONS.length];
        for (int i = 0; i < RESOLUTIONS.length; i++) {
            Dimension dimension = RESOLUTIONS[i];
            labels[i] = dimension.width == 0 ? "Native" : dimension.width + " × " + dimension.height;
        }
        return labels;
    }

    public int masterVolume() { return masterVolume; }
    public void setMasterVolume(int value) {
        masterVolume = clamp(value, 0, 100);
        applyAudioSettings();
        save();
    }

    public int musicVolume() { return musicVolume; }
    public void setMusicVolume(int value) {
        musicVolume = clamp(value, 0, 100);
        save();
    }

    public int effectsVolume() { return effectsVolume; }
    public void setEffectsVolume(int value) {
        effectsVolume = clamp(value, 0, 100);
        applyAudioSettings();
        save();
    }

    public boolean tutorialHints() { return tutorialHints; }
    public void setTutorialHints(boolean value) { tutorialHints = value; save(); }
    public boolean pauseOnFocusLost() { return pauseOnFocusLost; }
    public void setPauseOnFocusLost(boolean value) { pauseOnFocusLost = value; save(); }
    public boolean edgeScrolling() { return edgeScrolling; }
    public void setEdgeScrolling(boolean value) { edgeScrolling = value; save(); }
    public boolean confirmDangerousActions() { return confirmDangerousActions; }
    public void setConfirmDangerousActions(boolean value) { confirmDangerousActions = value; save(); }

    public String hudDebugSuffix(boolean devMode) {
        return devMode ? " | Performance: " + bindingText("performance_overlay") : "";
    }

    public String hudCommandLine() {
        return "Commands: " + bindingText("attack_move") + " attack-move | "
                + bindingText("patrol") + " patrol | "
                + bindingText("guard") + " guard | "
                + bindingText("escort") + " escort | "
                + bindingText("hold") + " hold | "
                + bindingText("stop_orders") + " stop";
    }

    int effectiveEffectsVolumeForTest() { return effectiveEffectsVolume(); }

    private int effectiveEffectsVolume() {
        return clamp((int)Math.round(masterVolume * effectsVolume / 100.0), 0, 100);
    }

    private void applyAudioSettings() {
        ProceduralAudio.setVolumePercent(effectiveEffectsVolume());
    }

    private Binding findConflict(String excludedActionId, int keyCode, boolean ctrlRequired) {
        for (Binding binding : bindings.values()) {
            if (binding.actionId.equals(excludedActionId)) continue;
            if (binding.keyCode == keyCode && binding.ctrlRequired == ctrlRequired) return binding;
        }
        return null;
    }

    private void read() {
        if (!Files.exists(CONFIG_PATH)) return;
        Properties props = new Properties();
        try (InputStream input = Files.newInputStream(CONFIG_PATH)) {
            props.load(input);
        } catch (IOException ex) {
            System.err.println("Could not load StarChem settings: " + ex.getMessage());
            return;
        }
        fullscreen = Boolean.parseBoolean(props.getProperty("display.fullscreen", Boolean.toString(fullscreen)));
        showFps = Boolean.parseBoolean(props.getProperty("display.showFps", Boolean.toString(showFps)));
        resolutionIndex = clamp(parseInt(props.getProperty("display.resolutionIndex"), resolutionIndex), 0, RESOLUTIONS.length - 1);
        masterVolume = clamp(parseInt(props.getProperty("audio.masterVolume"), masterVolume), 0, 100);
        musicVolume = clamp(parseInt(props.getProperty("audio.musicVolume"), musicVolume), 0, 100);
        effectsVolume = clamp(parseInt(props.getProperty("audio.effectsVolume"), effectsVolume), 0, 100);
        tutorialHints = Boolean.parseBoolean(props.getProperty("gameplay.tutorialHints", Boolean.toString(tutorialHints)));
        pauseOnFocusLost = Boolean.parseBoolean(props.getProperty("gameplay.pauseOnFocusLost", Boolean.toString(pauseOnFocusLost)));
        edgeScrolling = Boolean.parseBoolean(props.getProperty("gameplay.edgeScrolling", Boolean.toString(edgeScrolling)));
        confirmDangerousActions = Boolean.parseBoolean(props.getProperty("gameplay.confirmDangerousActions", Boolean.toString(confirmDangerousActions)));
        for (Binding binding : bindings.values()) {
            binding.set(
                    parseInt(props.getProperty("bind." + binding.actionId + ".keyCode"), binding.keyCode),
                    Boolean.parseBoolean(props.getProperty("bind." + binding.actionId + ".ctrlRequired", Boolean.toString(binding.ctrlRequired))));
        }
    }

    private void seedDefaults() {
        add("Camera", "Camera Left (WASD)", "camera_left_wasd", KeyEvent.VK_A, false, true);
        add("Camera", "Camera Left (Arrows)", "camera_left_arrow", KeyEvent.VK_LEFT, false, true);
        add("Camera", "Camera Right (WASD)", "camera_right_wasd", KeyEvent.VK_D, false, true);
        add("Camera", "Camera Right (Arrows)", "camera_right_arrow", KeyEvent.VK_RIGHT, false, true);
        add("Camera", "Camera Up (WASD)", "camera_up_wasd", KeyEvent.VK_W, false, true);
        add("Camera", "Camera Up (Arrows)", "camera_up_arrow", KeyEvent.VK_UP, false, true);
        add("Camera", "Camera Down (WASD)", "camera_down_wasd", KeyEvent.VK_S, false, true);
        add("Camera", "Camera Down (Arrows)", "camera_down_arrow", KeyEvent.VK_DOWN, false, true);
        add("Interface", "Galaxy Map", "galaxy_map", KeyEvent.VK_M, false, true);
        add("Interface", "Inventory", "inventory", KeyEvent.VK_I, false, true);
        add("Interface", "Narration", "narration", KeyEvent.VK_F8, false, true);
        add("Interface", "Chat", "chat_open", KeyEvent.VK_ENTER, false, true);
        add("Interface", "Fleet Formation", "formation", KeyEvent.VK_F, false, true);
        add("Interface", "Miner Range Overlay", "miner_range", KeyEvent.VK_R, false, true);
        add("Interface", "Mute Audio", "mute_audio", KeyEvent.VK_M, true, true);
        add("Orders", "Attack Move", "attack_move", KeyEvent.VK_X, false, true);
        add("Orders", "Patrol", "patrol", KeyEvent.VK_P, false, true);
        add("Orders", "Guard", "guard", KeyEvent.VK_G, false, true);
        add("Orders", "Escort", "escort", KeyEvent.VK_E, false, true);
        add("Orders", "Hold Position", "hold", KeyEvent.VK_H, false, true);
        add("Orders", "Stop / Clear Orders", "stop_orders", KeyEvent.VK_C, false, true);
        add("Debug", "AI Debug Overlay", "ai_debug_overlay", KeyEvent.VK_F3, false, true);
        add("Debug", "Performance Overlay", "performance_overlay", KeyEvent.VK_F4, false, true);
        add("System", "Pause Menu", "pause_menu", KeyEvent.VK_ESCAPE, false, false);
    }

    private void add(String group, String label, String actionId,
                     int keyCode, boolean ctrlRequired, boolean editable) {
        bindings.put(actionId, new Binding(group, label, actionId, keyCode, ctrlRequired, editable));
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); }
        catch (RuntimeException ex) { return fallback; }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
