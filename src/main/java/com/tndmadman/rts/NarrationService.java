package com.tndmadman.rts;

import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

final class NarrationService {
    static final String SYSTEM_DEFAULT = "System default";
    private static final Preferences PREFS = Preferences.userNodeForPackage(NarrationService.class);
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(12), runnable -> {
        Thread thread = new Thread(runnable, "StarChem Narration");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.DiscardOldestPolicy());

    private static volatile boolean enabled = PREFS.getBoolean("enabled", true);
    private static volatile int volume = clamp(PREFS.getInt("volume", 75), 0, 100);
    private static volatile double speed = clamp(PREFS.getDouble("speed", 1.0), 0.5, 2.0);
    private static volatile String voice = PREFS.get("voice", SYSTEM_DEFAULT);
    private static volatile List<String> voices;

    private NarrationService() { }

    static boolean enabled() { return enabled; }
    static int volume() { return volume; }
    static double speed() { return speed; }
    static String voice() { return voice == null || voice.isBlank() ? SYSTEM_DEFAULT : voice; }

    static void setEnabled(boolean value) {
        enabled = value;
        putBoolean("enabled", value);
    }

    static void toggle() { setEnabled(!enabled); }

    static void setVolume(int value) {
        volume = clamp(value, 0, 100);
        putInt("volume", volume);
    }

    static void setSpeed(double value) {
        speed = clamp(value, 0.5, 2.0);
        putDouble("speed", speed);
    }

    static void setVoice(String value) {
        String selected = value == null || value.isBlank() ? SYSTEM_DEFAULT : value;
        voice = voices().contains(selected) ? selected : SYSTEM_DEFAULT;
        put("voice", voice);
    }

    static void previousVoice() {
        List<String> available = voices();
        int index = available.indexOf(voice());
        setVoice(available.get(Math.floorMod(index - 1, available.size())));
    }

    static void nextVoice() {
        List<String> available = voices();
        int index = available.indexOf(voice());
        setVoice(available.get(Math.floorMod(index + 1, available.size())));
    }

    static List<String> voices() {
        List<String> cached = voices;
        if (cached != null) return cached;
        synchronized (NarrationService.class) {
            if (voices == null) voices = discoverVoices();
            if (!voices.contains(voice())) voice = SYSTEM_DEFAULT;
            return voices;
        }
    }

    static String backendLabel() {
        if (isWindows()) return "Windows System Speech";
        if (isMac()) return "macOS say";
        return linuxExecutable().isBlank() ? "No Linux TTS command found" : linuxExecutable();
    }

    static void testVoice() {
        speak("StarChem narration online.");
    }

    static void speak(String text) {
        if (!enabled || volume <= 0 || GraphicsEnvironment.isHeadless() || text == null || text.isBlank()) return;
        String clean = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.length() > 350) clean = clean.substring(0, 347) + "...";
        final String spoken = clean;
        final String selectedVoice = voice();
        final int selectedVolume = volume;
        final double selectedSpeed = speed;
        EXECUTOR.execute(() -> speakNow(spoken, selectedVoice, selectedVolume, selectedSpeed));
    }

    private static void speakNow(String text, String selectedVoice, int selectedVolume, double selectedSpeed) {
        try {
            if (isWindows()) speakWindows(text, selectedVoice, selectedVolume, selectedSpeed);
            else if (isMac()) speakMac(text, selectedVoice, selectedVolume, selectedSpeed);
            else speakLinux(text, selectedVoice, selectedVolume, selectedSpeed);
        } catch (Exception ex) {
            System.err.println("Narration unavailable: " + ex.getMessage());
        }
    }

    private static void speakWindows(String text, String selectedVoice, int selectedVolume, double selectedSpeed)
            throws IOException, InterruptedException {
        String shell = windowsPowerShell();
        if (shell.isBlank()) return;
        String script = "Add-Type -AssemblyName System.Speech; "
                + "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                + "if($env:STARCHEM_TTS_VOICE -and $env:STARCHEM_TTS_VOICE -ne 'System default'){"
                + "try{$s.SelectVoice($env:STARCHEM_TTS_VOICE)}catch{}}; "
                + "$s.Volume=[int]$env:STARCHEM_TTS_VOLUME; "
                + "$s.Rate=[int]$env:STARCHEM_TTS_RATE; "
                + "$s.Speak($env:STARCHEM_TTS_TEXT); $s.Dispose();";
        ProcessBuilder builder = new ProcessBuilder(shell, "-NoProfile", "-NonInteractive", "-Command", script);
        builder.environment().put("STARCHEM_TTS_TEXT", text);
        builder.environment().put("STARCHEM_TTS_VOICE", selectedVoice);
        builder.environment().put("STARCHEM_TTS_VOLUME", Integer.toString(selectedVolume));
        int rate = clamp((int)Math.round((selectedSpeed - 1.0) * 8.0), -10, 10);
        builder.environment().put("STARCHEM_TTS_RATE", Integer.toString(rate));
        runSpeech(builder);
    }

    private static void speakMac(String text, String selectedVoice, int selectedVolume, double selectedSpeed)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("say");
        if (!SYSTEM_DEFAULT.equals(selectedVoice)) {
            command.add("-v");
            command.add(selectedVoice);
        }
        command.add("-r");
        command.add(Integer.toString(clamp((int)Math.round(190 * selectedSpeed), 90, 420)));
        command.add(text);
        runSpeech(new ProcessBuilder(command));
    }

    private static void speakLinux(String text, String selectedVoice, int selectedVolume, double selectedSpeed)
            throws IOException, InterruptedException {
        String executable = linuxExecutable();
        if (executable.isBlank()) return;
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("-a");
        command.add(Integer.toString(clamp(selectedVolume * 2, 0, 200)));
        command.add("-s");
        command.add(Integer.toString(clamp((int)Math.round(175 * selectedSpeed), 80, 450)));
        if (!SYSTEM_DEFAULT.equals(selectedVoice)) {
            command.add("-v");
            command.add(selectedVoice);
        }
        command.add(text);
        runSpeech(new ProcessBuilder(command));
    }

    private static void runSpeech(ProcessBuilder builder) throws IOException, InterruptedException {
        builder.redirectErrorStream(true);
        Process process = builder.start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
        }
    }

    private static List<String> discoverVoices() {
        Set<String> found = new LinkedHashSet<>();
        found.add(SYSTEM_DEFAULT);
        try {
            if (isWindows()) discoverWindowsVoices(found);
            else if (isMac()) discoverMacVoices(found);
            else discoverLinuxVoices(found);
        } catch (Exception ex) {
            System.err.println("Could not list narration voices: " + ex.getMessage());
        }
        return List.copyOf(found);
    }

    private static void discoverWindowsVoices(Set<String> found) throws IOException, InterruptedException {
        String shell = windowsPowerShell();
        if (shell.isBlank()) return;
        String script = "Add-Type -AssemblyName System.Speech; "
                + "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                + "$s.GetInstalledVoices()|ForEach-Object{$_.VoiceInfo.Name}; $s.Dispose();";
        addLines(found, runAndRead(List.of(shell, "-NoProfile", "-NonInteractive", "-Command", script), 4));
    }

    private static void discoverMacVoices(Set<String> found) throws IOException, InterruptedException {
        for (String line : runAndRead(List.of("say", "-v", "?"), 4)) {
            String[] columns = line.trim().split("\\s{2,}");
            if (columns.length > 0 && !columns[0].isBlank()) found.add(columns[0].trim());
        }
    }

    private static void discoverLinuxVoices(Set<String> found) throws IOException, InterruptedException {
        String executable = linuxExecutable();
        if (executable.isBlank()) return;
        for (String line : runAndRead(List.of(executable, "--voices"), 4)) {
            String[] columns = line.trim().split("\\s+");
            if (columns.length >= 4 && Character.isDigit(columns[0].charAt(0))) found.add(columns[3]);
        }
    }

    private static List<String> runAndRead(List<String> command, int timeoutSeconds)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(1, TimeUnit.SECONDS);
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) if (!line.isBlank()) lines.add(line.trim());
        }
        return lines;
    }

    private static void addLines(Set<String> found, List<String> lines) {
        for (String line : lines) if (line != null && !line.isBlank()) found.add(line.trim());
    }

    private static String windowsPowerShell() {
        if (commandAvailable("powershell.exe")) return "powershell.exe";
        if (commandAvailable("powershell")) return "powershell";
        return "";
    }

    private static String linuxExecutable() {
        if (commandAvailable("espeak-ng")) return "espeak-ng";
        if (commandAvailable("espeak")) return "espeak";
        return "";
    }

    private static boolean commandAvailable(String command) {
        try {
            Process process = new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isWindows() { return osName().contains("win"); }
    private static boolean isMac() { return osName().contains("mac"); }
    private static String osName() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT); }

    private static void put(String key, String value) { try { PREFS.put(key, value); } catch (SecurityException ignored) { } }
    private static void putBoolean(String key, boolean value) { try { PREFS.putBoolean(key, value); } catch (SecurityException ignored) { } }
    private static void putInt(String key, int value) { try { PREFS.putInt(key, value); } catch (SecurityException ignored) { } }
    private static void putDouble(String key, double value) { try { PREFS.putDouble(key, value); } catch (SecurityException ignored) { } }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
