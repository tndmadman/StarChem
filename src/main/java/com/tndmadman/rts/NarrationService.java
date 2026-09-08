package com.tndmadman.rts;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
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
    static final boolean DEFAULT_ENABLED = false;
    static final String ENABLED_PREF_KEY = "enabled.v2";
    private static final int BACKEND_PROBE_TIMEOUT_SECONDS = 4;
    private static final Preferences PREFS = Preferences.userNodeForPackage(NarrationService.class);
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(12), runnable -> {
        Thread thread = new Thread(runnable, "StarChem Narration");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.DiscardOldestPolicy());

    private static volatile boolean enabled = PREFS.getBoolean(ENABLED_PREF_KEY, DEFAULT_ENABLED);
    private static volatile int volume = clamp(PREFS.getInt("volume", 75), 0, 100);
    private static volatile double speed = clamp(PREFS.getDouble("speed", 1.5), 0.5, 2.0);
    private static volatile String voice = PREFS.get("voice", SYSTEM_DEFAULT);
    private static volatile List<String> voices;
    private static volatile Backend backend;

    private NarrationService() { }

    static boolean enabled() { return enabled; }
    static int volume() { return volume; }
    static double speed() { return speed; }
    static String voice() { return voice == null || voice.isBlank() ? SYSTEM_DEFAULT : voice; }

    static void setEnabled(boolean value) {
        enabled = value;
        putBoolean(ENABLED_PREF_KEY, value);
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

    static String backendLabel() { return backend().label(); }
    static boolean backendAvailable() { return backend().available(); }

    static void testVoice() {
        enqueueSpeech("StarChem narration online.", true);
    }

    static void speak(String text) {
        enqueueSpeech(text, false);
    }

    private static void enqueueSpeech(String text, boolean allowWhenDisabled) {
        if ((!allowWhenDisabled && !enabled) || volume <= 0 || GraphicsEnvironment.isHeadless()
                || text == null || text.isBlank()) return;
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
            Backend selectedBackend = backend();
            if (!selectedBackend.available()) {
                throw new IOException(selectedBackend.label());
            }
            if (isWindows()) speakWindows(selectedBackend.executable(), text, selectedVoice, selectedVolume, selectedSpeed);
            else if (isMac()) speakMac(selectedBackend.executable(), text, selectedVoice, selectedVolume, selectedSpeed);
            else speakLinux(selectedBackend.executable(), text, selectedVoice, selectedVolume, selectedSpeed);
        } catch (Exception ex) {
            System.err.println("Narration unavailable: " + ex.getMessage());
        }
    }

    private static void speakWindows(String shell, String text, String selectedVoice, int selectedVolume,
                                     double selectedSpeed) throws IOException, InterruptedException {
        String script = "$ErrorActionPreference='Stop'; try { "
                + "Add-Type -AssemblyName System.Speech -ErrorAction Stop; "
                + "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                + "try { "
                + "if($env:STARCHEM_TTS_VOICE -and $env:STARCHEM_TTS_VOICE -ne 'System default'){"
                + "try{$s.SelectVoice($env:STARCHEM_TTS_VOICE)}catch{}}; "
                + "$s.Volume=[int]$env:STARCHEM_TTS_VOLUME; "
                + "$s.Rate=[int]$env:STARCHEM_TTS_RATE; "
                + "$s.Speak($env:STARCHEM_TTS_TEXT); "
                + "} finally { $s.Dispose() }; exit 0 "
                + "} catch { Write-Error $_; exit 1 }";
        ProcessBuilder builder = new ProcessBuilder(shell, "-NoProfile", "-NonInteractive", "-Command", script);
        builder.environment().put("STARCHEM_TTS_TEXT", text);
        builder.environment().put("STARCHEM_TTS_VOICE", selectedVoice);
        builder.environment().put("STARCHEM_TTS_VOLUME", Integer.toString(selectedVolume));
        int rate = clamp((int)Math.round((selectedSpeed - 1.0) * 8.0), -10, 10);
        builder.environment().put("STARCHEM_TTS_RATE", Integer.toString(rate));
        runSpeech(builder);
    }

    private static void speakMac(String executable, String text, String selectedVoice, int selectedVolume,
                                 double selectedSpeed) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(executable);
        if (!SYSTEM_DEFAULT.equals(selectedVoice)) {
            command.add("-v");
            command.add(selectedVoice);
        }
        command.add("-r");
        command.add(Integer.toString(clamp((int)Math.round(190 * selectedSpeed), 90, 420)));
        command.add(text);
        runSpeech(new ProcessBuilder(command));
    }

    private static void speakLinux(String executable, String text, String selectedVoice, int selectedVolume,
                                   double selectedSpeed) throws IOException, InterruptedException {
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
        NarrationProcessRunner.CaptureResult result = NarrationProcessRunner.runAndRead(builder, 30);
        if (result.timedOut()) throw new IOException("Narration process timed out.");
        if (result.exitCode() != 0) {
            String diagnostic = result.lines().isEmpty() ? "" : " " + compactDiagnostic(result.lines());
            throw new IOException("Narration process exited with code " + result.exitCode() + "." + diagnostic);
        }
    }

    private static String compactDiagnostic(List<String> lines) {
        String joined = String.join(" ", lines.subList(0, Math.min(3, lines.size()))).trim();
        return joined.length() <= 240 ? joined : joined.substring(0, 237) + "...";
    }

    private static List<String> discoverVoices() {
        Set<String> found = new LinkedHashSet<>();
        found.add(SYSTEM_DEFAULT);
        Backend selectedBackend = backend();
        if (!selectedBackend.available()) return List.copyOf(found);
        try {
            if (isWindows()) discoverWindowsVoices(found, selectedBackend.executable());
            else if (isMac()) discoverMacVoices(found, selectedBackend.executable());
            else discoverLinuxVoices(found, selectedBackend.executable());
        } catch (Exception ex) {
            System.err.println("Could not list narration voices: " + ex.getMessage());
        }
        return List.copyOf(found);
    }

    private static void discoverWindowsVoices(Set<String> found, String shell)
            throws IOException, InterruptedException {
        String script = "$ErrorActionPreference='Stop'; try { "
                + "Add-Type -AssemblyName System.Speech -ErrorAction Stop; "
                + "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                + "try { $s.GetInstalledVoices()|ForEach-Object{$_.VoiceInfo.Name} } "
                + "finally { $s.Dispose() }; exit 0 "
                + "} catch { Write-Error $_; exit 1 }";
        addLines(found, runAndRead(List.of(shell, "-NoProfile", "-NonInteractive", "-Command", script), 4));
    }

    private static void discoverMacVoices(Set<String> found, String executable)
            throws IOException, InterruptedException {
        for (String line : runAndRead(List.of(executable, "-v", "?"), 4)) {
            String[] columns = line.trim().split("\\s{2,}");
            if (columns.length > 0 && !columns[0].isBlank()) found.add(columns[0].trim());
        }
    }

    private static void discoverLinuxVoices(Set<String> found, String executable)
            throws IOException, InterruptedException {
        for (String line : runAndRead(List.of(executable, "--voices"), 4)) {
            String[] columns = line.trim().split("\\s+");
            if (columns.length >= 4 && Character.isDigit(columns[0].charAt(0))) found.add(columns[3]);
        }
    }

    private static List<String> runAndRead(List<String> command, int timeoutSeconds)
            throws IOException, InterruptedException {
        NarrationProcessRunner.CaptureResult result = NarrationProcessRunner.runAndRead(
                new ProcessBuilder(command), timeoutSeconds);
        if (result.timedOut() || result.exitCode() != 0) return List.of();
        return result.lines();
    }

    private static void addLines(Set<String> found, List<String> lines) {
        for (String line : lines) if (line != null && !line.isBlank()) found.add(line.trim());
    }

    private static Backend backend() {
        Backend cached = backend;
        if (cached != null) return cached;
        synchronized (NarrationService.class) {
            if (backend == null) backend = detectBackend();
            return backend;
        }
    }

    private static Backend detectBackend() {
        if (isWindows()) {
            for (String shell : List.of("powershell.exe", "powershell", "pwsh.exe", "pwsh")) {
                if (commandAvailable(windowsSpeechProbeCommand(shell), BACKEND_PROBE_TIMEOUT_SECONDS)) {
                    return new Backend(shell, "Windows System Speech");
                }
            }
            return Backend.unavailable("Windows TTS unavailable");
        }
        if (isMac()) {
            if (commandAvailable(List.of("say", "-v", "?"), BACKEND_PROBE_TIMEOUT_SECONDS)) {
                return new Backend("say", "macOS say");
            }
            return Backend.unavailable("macOS TTS unavailable");
        }
        for (String executable : List.of("espeak-ng", "espeak")) {
            if (commandAvailable(List.of(executable, "--version"), 2)) {
                return new Backend(executable, executable);
            }
        }
        return Backend.unavailable("Linux TTS unavailable");
    }

    static List<String> windowsSpeechProbeCommand(String shell) {
        String script = "$ErrorActionPreference='Stop'; try { "
                + "Add-Type -AssemblyName System.Speech -ErrorAction Stop; "
                + "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                + "$s.Dispose(); exit 0 "
                + "} catch { exit 1 }";
        return List.of(shell, "-NoProfile", "-NonInteractive", "-Command", script);
    }

    private static boolean commandAvailable(List<String> command, int timeoutSeconds) {
        try {
            NarrationProcessRunner.ExitResult result = NarrationProcessRunner.runDiscarding(
                    new ProcessBuilder(command), timeoutSeconds);
            return !result.timedOut() && result.exitCode() == 0;
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

    private record Backend(String executable, String label) {
        static Backend unavailable(String label) { return new Backend("", label); }
        boolean available() { return executable != null && !executable.isBlank(); }
    }
}
