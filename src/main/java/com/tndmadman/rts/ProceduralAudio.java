package com.tndmadman.rts;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.prefs.Preferences;

/**
 * Tiny runtime synthesizer for StarChem.
 *
 * No wav/mp3/ogg files are loaded or generated. Sounds are mixed directly into
 * a Java Sound PCM line from oscillator, noise, pitch sweep, and envelope code.
 */
final class ProceduralAudio {
    private static final ProceduralAudio INSTANCE = new ProceduralAudio();
    private static final Preferences PREFS = Preferences.userNodeForPackage(ProceduralAudio.class);
    private static final String PREF_MUTED = "proceduralAudioMuted";
    private static final String PREF_VOLUME = "proceduralAudioVolumePercent";
    private static final float SAMPLE_RATE = 44_100f;
    private static final int BUFFER_FRAMES = 512;
    private static final int MAX_VOICES = 64;
    private static final double PITCH_VARIATION = 0.055;

    private final Object lock = new Object();
    private final List<Voice> voices = new ArrayList<>();
    private final Random random = new Random();

    private SourceDataLine line;
    private boolean attemptedStart;
    private boolean available = true;
    private volatile boolean muted = readBoolean(PREF_MUTED, false);
    private volatile int volumePercent = clampVolume(readInt(PREF_VOLUME, 80));

    private ProceduralAudio() { }

    static void prime() { INSTANCE.ensureStarted(); }
    static void play(SoundCue cue) { INSTANCE.playCue(cue); }

    static boolean toggleMute() {
        boolean nowMuted = INSTANCE.setMutedInternal(!INSTANCE.muted);
        if (!nowMuted) play(SoundCue.MUTE_OFF);
        return nowMuted;
    }

    static boolean setMuted(boolean muted) { return INSTANCE.setMutedInternal(muted); }
    static boolean muted() { return INSTANCE.muted; }
    static int volumePercent() { return INSTANCE.volumePercent; }
    static int setVolumePercent(int percent) { return INSTANCE.setVolumePercentInternal(percent); }
    static boolean available() { return INSTANCE.available; }
    static void playWeaponFire(WeaponType weapon, double distance) { INSTANCE.weaponFire(weapon, distance); }
    static void playWeaponImpact(WeaponType weapon) { INSTANCE.weaponImpact(weapon); }
    static void playDestruction(double scale) { INSTANCE.destruction(scale); }
    static void playResourceDepleted(Material material) { INSTANCE.resourceDepleted(material); }

    private synchronized boolean setMutedInternal(boolean value) {
        muted = value;
        writeBoolean(PREF_MUTED, value);
        if (muted) {
            synchronized (lock) { voices.clear(); }
        }
        return muted;
    }

    private synchronized int setVolumePercentInternal(int percent) {
        volumePercent = clampVolume(percent);
        writeInt(PREF_VOLUME, volumePercent);
        return volumePercent;
    }

    private void playCue(SoundCue cue) {
        if (cue == null) return;
        switch (cue) {
            case SELECT -> add(
                    voice(Wave.SINE, 540, 780, 0.070, 0.11, 0.0, 0.004, 0.055),
                    voice(Wave.TRIANGLE, 920, 1120, 0.045, 0.055, 0.0, 0.003, 0.035));
            case MOVE_ORDER -> add(
                    voice(Wave.TRIANGLE, 220, 360, 0.090, 0.11, 0.0, 0.006, 0.070),
                    voice(Wave.SINE, 480, 620, 0.110, 0.055, 0.0, 0.010, 0.090));
            case HARVEST_ORDER -> add(
                    voice(Wave.TRIANGLE, 180, 130, 0.140, 0.105, 0.16, 0.010, 0.110),
                    voice(Wave.SINE, 520, 440, 0.090, 0.045, 0.0, 0.004, 0.070));
            case ATTACK_ORDER -> add(
                    voice(Wave.SQUARE, 230, 95, 0.120, 0.13, 0.12, 0.002, 0.095),
                    voice(Wave.SINE, 880, 1180, 0.055, 0.055, 0.0, 0.002, 0.045));
            case BUILD_SHIP -> add(
                    voice(Wave.TRIANGLE, 420, 840, 0.180, 0.11, 0.0, 0.010, 0.150),
                    voice(Wave.SINE, 630, 1260, 0.145, 0.075, 0.0, 0.020, 0.110));
            case PACKAGE_LOAD -> add(
                    voice(Wave.SQUARE, 130, 95, 0.100, 0.12, 0.18, 0.002, 0.080),
                    voice(Wave.TRIANGLE, 330, 260, 0.075, 0.055, 0.0, 0.002, 0.055));
            case PLACE_STATION -> add(
                    voice(Wave.SINE, 105, 64, 0.240, 0.16, 0.22, 0.006, 0.210),
                    voice(Wave.TRIANGLE, 360, 540, 0.170, 0.075, 0.0, 0.030, 0.120));
            case CRAFT_ITEM -> add(
                    voice(Wave.SINE, 500, 740, 0.080, 0.080, 0.0, 0.004, 0.060),
                    voice(Wave.SINE, 760, 980, 0.105, 0.060, 0.0, 0.030, 0.075),
                    voice(Wave.TRIANGLE, 1030, 1320, 0.085, 0.045, 0.0, 0.050, 0.060));
            case ERROR -> add(voice(Wave.SQUARE, 170, 86, 0.130, 0.11, 0.0, 0.002, 0.115));
            case RESOURCE_DEPLETED -> add(
                    voice(Wave.NOISE, 220, 80, 0.180, 0.12, 0.75, 0.002, 0.160),
                    voice(Wave.SINE, 130, 72, 0.190, 0.070, 0.0, 0.010, 0.170));
            case TRACTOR_BEAM -> add(
                    voice(Wave.SAW, 145, 210, 0.115, 0.050, 0.05, 0.012, 0.070),
                    voice(Wave.SINE, 520, 390, 0.130, 0.032, 0.0, 0.018, 0.080),
                    voice(Wave.NOISE, 180, 120, 0.090, 0.020, 0.82, 0.004, 0.060));
            case ITEM_PICKUP -> add(
                    voice(Wave.TRIANGLE, 360, 760, 0.075, 0.085, 0.0, 0.003, 0.050),
                    voice(Wave.SINE, 920, 1240, 0.060, 0.052, 0.0, 0.004, 0.042),
                    voice(Wave.NOISE, 640, 260, 0.045, 0.030, 0.55, 0.001, 0.038));
            case MUTE_OFF -> add(voice(Wave.SINE, 360, 720, 0.080, 0.10, 0.0, 0.004, 0.065));
        }
    }

    private void weaponFire(WeaponType weapon, double distance) {
        if (weapon == null) return;
        double strength = clamp(weapon.damage / 260.0, 0.22, 1.35);
        double spread = clamp(distance / Math.max(1, weapon.range), 0.15, 1.0);
        if (weapon.beam) {
            add(
                    voice(Wave.SAW, 740 + 220 * strength, 360 + 90 * strength, 0.090 + 0.055 * strength, 0.075 * strength, 0.02, 0.002, 0.075),
                    voice(Wave.SINE, 1460, 920, 0.060 + 0.030 * strength, 0.035 * strength, 0.0, 0.002, 0.050));
            return;
        }
        if (weapon.movingShot) {
            add(
                    voice(Wave.NOISE, 160, 80, 0.130 + 0.050 * strength, 0.080 * strength, 0.55, 0.002, 0.120),
                    voice(Wave.TRIANGLE, 180 + 70 * strength, 120, 0.130, 0.055 * strength, 0.0, 0.004, 0.105));
            return;
        }
        add(
                voice(Wave.SQUARE, 260 + 80 * strength, 90 + 60 * spread, 0.070 + 0.055 * strength, 0.095 * strength, 0.18, 0.001, 0.060),
                voice(Wave.NOISE, 180, 70, 0.070 + 0.030 * strength, 0.050 * strength, 0.65, 0.001, 0.055));
    }

    private void weaponImpact(WeaponType weapon) {
        if (weapon == null) return;
        double strength = clamp(weapon.damage / 240.0, 0.20, 1.45);
        add(
                voice(Wave.NOISE, 190, 60, 0.085 + 0.070 * strength, 0.095 * strength, 0.80, 0.001, 0.080),
                voice(Wave.SINE, 150 + 70 * strength, 55, 0.090 + 0.050 * strength, 0.060 * strength, 0.0, 0.003, 0.085));
    }

    private void destruction(double scale) {
        double power = clamp(scale, 0.7, 5.0);
        add(
                voice(Wave.NOISE, 120, 25, 0.240 + 0.065 * power, 0.120 + 0.030 * power, 0.92, 0.001, 0.220 + 0.035 * power),
                voice(Wave.SINE, 92 / Math.sqrt(power), 38 / Math.sqrt(power), 0.300 + 0.070 * power, 0.090 + 0.020 * power, 0.0, 0.006, 0.260),
                voice(Wave.SQUARE, 260, 120, 0.040 + 0.010 * power, 0.045 * power, 0.25, 0.001, 0.035));
    }

    private void resourceDepleted(Material material) {
        if (material == null) {
            playCue(SoundCue.RESOURCE_DEPLETED);
            return;
        }
        double baseHz = switch (material.family) {
            case METAL, SALVAGE, ALLOY, COMPOSITE, INDUSTRIAL, CAPITAL ->
                    material == Material.CIRCUIT_FRAGMENTS ? 760 : 150;
            case MINERAL -> 185;
            case VOLATILE -> 620;
            case GAS -> 310;
            case REFINED, POWER -> 220;
            case CHEMICAL -> 420;
            case ELECTRONIC -> 760;
            case WEAPON -> 190;
        };
        double noise = material == Material.ICE || material == Material.CIRCUIT_FRAGMENTS ? 0.18 : 0.45;
        add(
                voice(Wave.TRIANGLE, baseHz, baseHz * 0.52, 0.170, 0.090, noise, 0.002, 0.145),
                voice(Wave.NOISE, baseHz * 0.7, 70, 0.115, 0.060, 0.85, 0.001, 0.095));
    }

    private Voice voice(Wave wave, double startHz, double endHz, double duration, double volume, double noise,
                        double attack, double decay) {
        double pitch = randomPitchFactor();
        return new Voice(wave, startHz * pitch, endHz * pitch, duration, volume, noise, attack, decay,
                randomPan(), random.nextLong());
    }

    private double randomPitchFactor() {
        return 1.0 + (random.nextDouble() * 2.0 - 1.0) * PITCH_VARIATION;
    }

    private double randomPan() { return random.nextDouble() * 0.42 - 0.21; }

    private void add(Voice... newVoices) {
        ensureStarted();
        if (!available || muted || volumePercent <= 0 || newVoices == null || newVoices.length == 0) return;
        synchronized (lock) {
            for (Voice voice : newVoices) {
                if (voice == null) continue;
                while (voices.size() >= MAX_VOICES) voices.remove(0);
                voices.add(voice);
            }
        }
    }

    private synchronized void ensureStarted() {
        if (attemptedStart) return;
        attemptedStart = true;
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
            line = AudioSystem.getSourceDataLine(format);
            line.open(format, BUFFER_FRAMES * 4 * 4);
            line.start();
            Thread mixer = new Thread(this::mixLoop, "StarChem Procedural Audio");
            mixer.setDaemon(true);
            mixer.start();
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException ex) {
            available = false;
            System.err.println("Procedural audio unavailable: " + ex.getMessage());
        }
    }

    private void mixLoop() {
        byte[] bytes = new byte[BUFFER_FRAMES * 4];
        while (available && line != null) {
            synchronized (lock) {
                double gain = effectiveGain(volumePercent, muted);
                for (int i = 0; i < BUFFER_FRAMES; i++) {
                    double left = 0;
                    double right = 0;
                    if (gain > 0) {
                        Iterator<Voice> it = voices.iterator();
                        while (it.hasNext()) {
                            Voice voice = it.next();
                            double sample = voice.nextSample();
                            if (voice.done()) it.remove();
                            left += sample * (1.0 - voice.pan) * 0.5;
                            right += sample * (1.0 + voice.pan) * 0.5;
                        }
                    }
                    int offset = i * 4;
                    writeSample(bytes, offset, left * 0.75 * gain);
                    writeSample(bytes, offset + 2, right * 0.75 * gain);
                }
            }
            line.write(bytes, 0, bytes.length);
        }
    }

    private static void writeSample(byte[] bytes, int offset, double sample) {
        int v = (int)Math.round(clamp(sample, -1.0, 1.0) * 32767.0);
        bytes[offset] = (byte)(v & 0xff);
        bytes[offset + 1] = (byte)((v >>> 8) & 0xff);
    }

    static double effectiveGain(int volumePercent, boolean muted) {
        return muted ? 0.0 : clamp(clampVolume(volumePercent) / 100.0, 0.0, 1.0);
    }

    private static int clampVolume(int value) { return Math.max(0, Math.min(100, value)); }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }

    private static boolean readBoolean(String key, boolean fallback) {
        try { return PREFS.getBoolean(key, fallback); }
        catch (SecurityException ignored) { return fallback; }
    }

    private static int readInt(String key, int fallback) {
        try { return PREFS.getInt(key, fallback); }
        catch (SecurityException ignored) { return fallback; }
    }

    private static void writeBoolean(String key, boolean value) {
        try { PREFS.putBoolean(key, value); }
        catch (SecurityException ignored) { }
    }

    private static void writeInt(String key, int value) {
        try { PREFS.putInt(key, value); }
        catch (SecurityException ignored) { }
    }

    private enum Wave { SINE, TRIANGLE, SQUARE, SAW, NOISE }

    private static final class Voice {
        private final Wave wave;
        private final double startHz;
        private final double endHz;
        private final double duration;
        private final double volume;
        private final double noise;
        private final double attack;
        private final double decay;
        private final double pan;
        private final Random random;
        private double age;
        private double phase;

        private Voice(Wave wave, double startHz, double endHz, double duration, double volume, double noise,
                      double attack, double decay, double pan, long seed) {
            this.wave = wave;
            this.startHz = Math.max(1, startHz);
            this.endHz = Math.max(1, endHz);
            this.duration = Math.max(0.005, duration);
            this.volume = Math.max(0, volume);
            this.noise = clamp(noise, 0, 1);
            this.attack = Math.max(0.001, attack);
            this.decay = Math.max(0.001, decay);
            this.pan = clamp(pan, -1, 1);
            this.random = new Random(seed);
        }

        private double nextSample() {
            double t = clamp(age / duration, 0, 1);
            double freq = startHz + (endHz - startHz) * t;
            phase += Math.PI * 2.0 * freq / SAMPLE_RATE;
            if (phase > Math.PI * 2.0) phase -= Math.PI * 2.0;

            double osc = switch (wave) {
                case SINE -> Math.sin(phase);
                case TRIANGLE -> 2.0 / Math.PI * Math.asin(Math.sin(phase));
                case SQUARE -> Math.sin(phase) >= 0 ? 1.0 : -1.0;
                case SAW -> {
                    double p = phase / (Math.PI * 2.0);
                    yield 2.0 * (p - Math.floor(p + 0.5));
                }
                case NOISE -> 0;
            };
            double hiss = random.nextDouble() * 2.0 - 1.0;
            double mixed = osc * (1.0 - noise) + hiss * noise;
            double env = envelope(age);
            age += 1.0 / SAMPLE_RATE;
            return mixed * env * volume;
        }

        private double envelope(double seconds) {
            if (seconds < attack) return seconds / attack;
            double remaining = duration - seconds;
            if (remaining <= 0) return 0;
            if (remaining < decay) return remaining / decay;
            return 1.0;
        }

        private boolean done() { return age >= duration; }
    }
}
