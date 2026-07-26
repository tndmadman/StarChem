package com.tndmadman.rts;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/** Retains bounded, age-limited last-seen moderation signals without authentication material. */
final class ServerPlayerObservationStore {
    private static final int MAX_PLAYERS = 10_000;
    private static final int MAX_SIGNALS_PER_PLAYER = 8;
    private static final int DEFAULT_RETENTION_DAYS = 90;
    private static final int DEFAULT_BAN_MAX_AGE_DAYS = 30;
    private static final int MAX_CONFIGURED_DAYS = 3650;
    private static final String RETENTION_PROPERTY = "starchem.observations.retentionDays";
    private static final String BAN_AGE_PROPERTY = "starchem.observations.banMaxAgeDays";
    private static final String RETENTION_ENV = "STARCHEM_OBSERVATION_RETENTION_DAYS";
    private static final String BAN_AGE_ENV = "STARCHEM_OBSERVATION_BAN_MAX_AGE_DAYS";

    private final Path path;
    private final long retentionMillis;
    private final long moderationMaxAgeMillis;
    private final LongSupplier clock;
    private final LinkedHashMap<String,PlayerObservation> observations = new LinkedHashMap<>();
    private boolean failNextSaveForTest;

    ServerPlayerObservationStore(Path saveDir, String saveName) {
        this(saveDir, saveName,
                configuredMillis(RETENTION_PROPERTY, RETENTION_ENV, DEFAULT_RETENTION_DAYS),
                configuredMillis(BAN_AGE_PROPERTY, BAN_AGE_ENV, DEFAULT_BAN_MAX_AGE_DAYS),
                System::currentTimeMillis);
    }

    ServerPlayerObservationStore(Path saveDir, String saveName, long retentionMillis,
                                 long moderationMaxAgeMillis, LongSupplier clock) {
        Path dir = saveDir == null ? Path.of("saves") : saveDir;
        path = dir.resolve(Config.cleanSaveName(saveName) + "-observations.json");
        this.retentionMillis = Math.max(1, retentionMillis);
        this.moderationMaxAgeMillis = Math.max(1, moderationMaxAgeMillis);
        this.clock = clock == null ? System::currentTimeMillis : clock;
        load();
    }

    synchronized void record(String playerId, String playerName, InetAddress address, String deviceId) {
        if (playerId == null || playerId.isBlank()) return;
        long now = now();
        LinkedHashMap<String,PlayerObservation> before = snapshot();
        pruneExpiredInternal(now);
        String key = key(playerId);
        PlayerObservation current = observations.get(key);
        PlayerObservation updated = (current == null ? PlayerObservation.empty(playerId, playerName) : current)
                .observe(playerName, address == null ? "" : address.getHostAddress(), deviceId, now);
        observations.put(key, updated);
        trim();
        try {
            save();
        } catch (IOException ex) {
            restore(before);
            System.err.println("Could not save player observations: " + ex.getMessage());
        }
    }

    synchronized PlayerObservation find(String selector) {
        pruneExpiredBestEffort();
        return findInternal(selector);
    }

    synchronized List<String> lines(String selector) {
        pruneExpiredBestEffort();
        ArrayList<PlayerObservation> rows = new ArrayList<>();
        if (selector == null || selector.isBlank()) rows.addAll(observations.values());
        else {
            PlayerObservation found = findInternal(selector);
            if (found != null) rows.add(found);
        }
        rows.sort(Comparator.comparingLong(PlayerObservation::lastSeenAt).reversed());
        if (rows.isEmpty()) return List.of("No player observations matched.");
        long now = now();
        ArrayList<String> lines = new ArrayList<>();
        for (PlayerObservation observation : rows) {
            lines.add(observation.playerId() + " | " + observation.playerName() + " | last seen "
                    + Instant.ofEpochMilli(observation.lastSeenAt()));
            for (SignalObservation signal : observation.ipSignals()) {
                lines.add("  IP " + signal.value() + " | last seen " + Instant.ofEpochMilli(signal.lastSeenAt())
                        + " | age " + age(now, signal.lastSeenAt()));
                if (lines.size() >= 100) return List.copyOf(lines);
            }
            for (SignalObservation signal : observation.deviceSignals()) {
                lines.add("  device " + ServerDeviceIdentity.mask(signal.value()) + " | last seen "
                        + Instant.ofEpochMilli(signal.lastSeenAt()) + " | age " + age(now, signal.lastSeenAt()));
                if (lines.size() >= 100) return List.copyOf(lines);
            }
        }
        return List.copyOf(lines);
    }

    synchronized ModerationSignals moderationSignals(String selector, boolean includeStale) {
        pruneExpiredBestEffort();
        PlayerObservation observation = findInternal(selector);
        if (observation == null) return ModerationSignals.empty();
        long cutoff = cutoff(now(), moderationMaxAgeMillis);
        ArrayList<String> ips = new ArrayList<>();
        ArrayList<String> devices = new ArrayList<>();
        int staleIps = collectForModeration(observation.ipSignals(), cutoff, includeStale, ips);
        int staleDevices = collectForModeration(observation.deviceSignals(), cutoff, includeStale, devices);
        return new ModerationSignals(List.copyOf(ips), List.copyOf(devices), staleIps, staleDevices);
    }

    synchronized MutationResult delete(String selector) {
        String selectedKey = findKey(selector);
        if (selectedKey == null) return new MutationResult(true, false, "No player observations matched.");
        LinkedHashMap<String,PlayerObservation> before = snapshot();
        PlayerObservation removed = observations.remove(selectedKey);
        try {
            save();
            return new MutationResult(true, true, "Deleted observations for " + removed.playerId() + ".");
        } catch (IOException ex) {
            restore(before);
            return new MutationResult(false, false, "Could not delete player observations: " + ex.getMessage());
        }
    }

    synchronized MutationResult clearAll() {
        if (observations.isEmpty()) return new MutationResult(true, false, "Player observations are already empty.");
        LinkedHashMap<String,PlayerObservation> before = snapshot();
        int count = observations.size();
        observations.clear();
        try {
            save();
            return new MutationResult(true, true, "Cleared " + count + " player observation record"
                    + (count == 1 ? "." : "s."));
        } catch (IOException ex) {
            restore(before);
            return new MutationResult(false, false, "Could not clear player observations: " + ex.getMessage());
        }
    }

    synchronized MutationResult pruneExpired() {
        LinkedHashMap<String,PlayerObservation> before = snapshot();
        PruneCounts counts = pruneExpiredInternal(now());
        if (!counts.changed()) return new MutationResult(true, false, "No expired observation signals found.");
        try {
            save();
            return new MutationResult(true, true, "Pruned " + counts.signals() + " expired signal"
                    + (counts.signals() == 1 ? "" : "s") + " and " + counts.players() + " empty player record"
                    + (counts.players() == 1 ? "." : "s."));
        } catch (IOException ex) {
            restore(before);
            return new MutationResult(false, false, "Could not prune player observations: " + ex.getMessage());
        }
    }

    synchronized void failNextSaveForTest() { failNextSaveForTest = true; }
    Path pathForTest() { return path; }

    private void load() {
        if (!Files.isRegularFile(path)) return;
        try {
            PrivateFileSecurity.secureFile(path);
            Object parsed = MiniJson.parse(Files.readString(path, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?,?> raw)) return;
            boolean needsMigration = number(raw.get("version")) < 2;
            Object players = raw.get("players");
            if (!(players instanceof List<?> list)) return;
            for (Object value : list) {
                if (!(value instanceof Map<?,?> row)) continue;
                String playerId = text(row.get("playerId"));
                if (playerId.isBlank()) continue;
                long fallbackSeenAt = number(row.get("lastSeenAt"));
                PlayerObservation observation = new PlayerObservation(playerId, text(row.get("playerName")),
                        signals(row.get("ips"), fallbackSeenAt, false),
                        signals(row.get("devices"), fallbackSeenAt, true), fallbackSeenAt);
                if (!observation.ipSignals().isEmpty() || !observation.deviceSignals().isEmpty()) {
                    observations.put(key(playerId), observation);
                }
            }
            trim();
            LinkedHashMap<String,PlayerObservation> before = snapshot();
            PruneCounts pruned = pruneExpiredInternal(now());
            if (needsMigration || pruned.changed()) {
                try {
                    save();
                } catch (IOException ex) {
                    restore(before);
                    System.err.println("Could not persist pruned player observations: " + ex.getMessage());
                }
            }
        } catch (Exception ex) {
            System.err.println("Could not load player observations: " + ex.getMessage());
        }
    }

    private void save() throws IOException {
        if (failNextSaveForTest) {
            failNextSaveForTest = false;
            throw new IOException("simulated observation save failure");
        }

        LinkedHashMap<String,Object> root = new LinkedHashMap<>();
        root.put("version", 2);
        root.put("retentionDays", Math.max(1, retentionMillis / Duration.ofDays(1).toMillis()));
        root.put("moderationMaxAgeDays", Math.max(1, moderationMaxAgeMillis / Duration.ofDays(1).toMillis()));
        ArrayList<Object> players = new ArrayList<>();
        for (PlayerObservation observation : observations.values()) {
            LinkedHashMap<String,Object> row = new LinkedHashMap<>();
            row.put("playerId", observation.playerId());
            row.put("playerName", observation.playerName());
            row.put("ips", signalRows(observation.ipSignals()));
            row.put("devices", signalRows(observation.deviceSignals()));
            row.put("lastSeenAt", observation.lastSeenAt());
            players.add(row);
        }
        root.put("players", players);
        root.put("updatedAt", Instant.ofEpochMilli(now()).toString());

        Path parent = path.toAbsolutePath().getParent();
        if (parent == null) throw new IOException("Player observation path has no parent directory: " + path);
        PrivateFileSecurity.ensurePrivateDirectory(parent);

        Path temp = null;
        try {
            temp = PrivateFileSecurity.createPrivateTempFile(parent,
                    path.getFileName().toString() + "-", ".tmp");
            Files.writeString(temp, MiniJson.stringify(root) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            PrivateFileSecurity.secureFile(temp);
            PrivateFileSecurity.moveReplace(temp, path);
            temp = null;
            PrivateFileSecurity.secureFile(path);
        } finally {
            if (temp != null) Files.deleteIfExists(temp);
        }
    }

    private void pruneExpiredBestEffort() {
        LinkedHashMap<String,PlayerObservation> before = snapshot();
        PruneCounts counts = pruneExpiredInternal(now());
        if (!counts.changed()) return;
        try {
            save();
        } catch (IOException ex) {
            restore(before);
            System.err.println("Could not prune expired player observations: " + ex.getMessage());
        }
    }

    private PruneCounts pruneExpiredInternal(long now) {
        long cutoff = cutoff(now, retentionMillis);
        int removedSignals = 0;
        int removedPlayers = 0;
        ArrayList<String> removeKeys = new ArrayList<>();
        for (Map.Entry<String,PlayerObservation> entry : observations.entrySet()) {
            PlayerObservation observation = entry.getValue();
            List<SignalObservation> ips = recent(observation.ipSignals(), cutoff);
            List<SignalObservation> devices = recent(observation.deviceSignals(), cutoff);
            removedSignals += observation.ipSignals().size() - ips.size();
            removedSignals += observation.deviceSignals().size() - devices.size();
            if (ips.isEmpty() && devices.isEmpty()) {
                removeKeys.add(entry.getKey());
                removedPlayers++;
            } else if (ips.size() != observation.ipSignals().size()
                    || devices.size() != observation.deviceSignals().size()) {
                entry.setValue(observation.withSignals(ips, devices));
            }
        }
        for (String removeKey : removeKeys) observations.remove(removeKey);
        return new PruneCounts(removedSignals, removedPlayers);
    }

    private PlayerObservation findInternal(String selector) {
        String selectedKey = findKey(selector);
        return selectedKey == null ? null : observations.get(selectedKey);
    }

    private String findKey(String selector) {
        if (selector == null || selector.isBlank()) return null;
        String wanted = key(selector);
        if (observations.containsKey(wanted)) return wanted;
        for (Map.Entry<String,PlayerObservation> entry : observations.entrySet()) {
            PlayerObservation observation = entry.getValue();
            if (observation.playerId().equalsIgnoreCase(selector)
                    || observation.playerName().equalsIgnoreCase(selector)) return entry.getKey();
        }
        return null;
    }

    private void trim() {
        if (observations.size() <= MAX_PLAYERS) return;
        ArrayList<PlayerObservation> sorted = new ArrayList<>(observations.values());
        sorted.sort(Comparator.comparingLong(PlayerObservation::lastSeenAt).reversed());
        observations.clear();
        for (int i = 0; i < Math.min(MAX_PLAYERS, sorted.size()); i++) {
            PlayerObservation observation = sorted.get(i);
            observations.put(key(observation.playerId()), observation);
        }
    }

    private LinkedHashMap<String,PlayerObservation> snapshot() {
        return new LinkedHashMap<>(observations);
    }

    private void restore(LinkedHashMap<String,PlayerObservation> snapshot) {
        observations.clear();
        observations.putAll(snapshot);
    }

    private static List<Object> signalRows(List<SignalObservation> signals) {
        ArrayList<Object> rows = new ArrayList<>();
        for (SignalObservation signal : signals) {
            LinkedHashMap<String,Object> row = new LinkedHashMap<>();
            row.put("value", signal.value());
            row.put("lastSeenAt", signal.lastSeenAt());
            rows.add(row);
        }
        return rows;
    }

    private static List<SignalObservation> signals(Object value, long fallbackSeenAt, boolean devices) {
        if (!(value instanceof List<?> list)) return List.of();
        ArrayList<SignalObservation> out = new ArrayList<>();
        for (Object item : list) {
            String signalValue;
            long lastSeenAt;
            if (item instanceof Map<?,?> row) {
                signalValue = text(row.get("value"));
                lastSeenAt = number(row.get("lastSeenAt"));
            } else {
                signalValue = text(item);
                lastSeenAt = fallbackSeenAt;
            }
            if (signalValue.isBlank() || devices && !ServerDeviceIdentity.valid(signalValue)) continue;
            if (out.stream().anyMatch(existing -> existing.value().equals(signalValue))) continue;
            out.add(new SignalObservation(signalValue, Math.max(0, lastSeenAt)));
            if (out.size() >= MAX_SIGNALS_PER_PLAYER) break;
        }
        return List.copyOf(out);
    }

    private static List<SignalObservation> recent(List<SignalObservation> signals, long cutoff) {
        ArrayList<SignalObservation> out = new ArrayList<>();
        for (SignalObservation signal : signals) {
            if (signal.lastSeenAt() >= cutoff) out.add(signal);
        }
        return List.copyOf(out);
    }

    private static int collectForModeration(List<SignalObservation> signals, long cutoff,
                                            boolean includeStale, List<String> selected) {
        int stale = 0;
        for (SignalObservation signal : signals) {
            if (signal.lastSeenAt() >= cutoff || includeStale) selected.add(signal.value());
            if (signal.lastSeenAt() < cutoff) stale++;
        }
        return stale;
    }

    private static List<SignalObservation> addSignal(List<SignalObservation> existing, String value, long at) {
        if (value == null || value.isBlank()) return existing == null ? List.of() : List.copyOf(existing);
        String normalized = value.trim();
        ArrayList<SignalObservation> out = new ArrayList<>();
        out.add(new SignalObservation(normalized, Math.max(0, at)));
        if (existing != null) {
            for (SignalObservation signal : existing) {
                if (signal == null || signal.value().isBlank() || signal.value().equals(normalized)) continue;
                out.add(signal);
                if (out.size() >= MAX_SIGNALS_PER_PLAYER) break;
            }
        }
        return List.copyOf(out);
    }

    private static long configuredMillis(String property, String environment, int defaultDays) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) value = System.getenv(environment);
        if (value == null || value.isBlank()) return Duration.ofDays(defaultDays).toMillis();
        try {
            int days = Integer.parseInt(value.trim());
            if (days < 1 || days > MAX_CONFIGURED_DAYS) throw new NumberFormatException();
            return Duration.ofDays(days).toMillis();
        } catch (NumberFormatException ex) {
            System.err.println("WARNING: " + property + " must be between 1 and " + MAX_CONFIGURED_DAYS
                    + " days; using " + defaultDays + ".");
            return Duration.ofDays(defaultDays).toMillis();
        }
    }

    private static long cutoff(long now, long ageMillis) {
        if (now <= ageMillis) return 0;
        return now - ageMillis;
    }

    private static String age(long now, long then) {
        long millis = Math.max(0, now - then);
        long days = Duration.ofMillis(millis).toDays();
        long hours = Duration.ofMillis(millis).minusDays(days).toHours();
        if (days > 0) return days + "d " + hours + "h";
        long minutes = Duration.ofMillis(millis).toMinutes();
        if (minutes > 0) return minutes + "m";
        return Math.max(0, millis / 1000) + "s";
    }

    private long now() {
        return Math.max(0, clock.getAsLong());
    }

    private static String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long number(Object value) {
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(text(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    record SignalObservation(String value, long lastSeenAt) {
        SignalObservation {
            value = value == null ? "" : value.trim();
            lastSeenAt = Math.max(0, lastSeenAt);
        }
    }

    static final class PlayerObservation {
        private final String playerId;
        private final String playerName;
        private final List<SignalObservation> ips;
        private final List<SignalObservation> devices;
        private final long lastSeenAt;

        PlayerObservation(String playerId, String playerName, List<SignalObservation> ips,
                          List<SignalObservation> devices, long lastSeenAt) {
            this.playerId = playerId == null ? "" : playerId.trim();
            this.playerName = Config.clean(playerName);
            this.ips = ips == null ? List.of() : List.copyOf(ips);
            this.devices = devices == null ? List.of() : List.copyOf(devices);
            this.lastSeenAt = Math.max(Math.max(0, lastSeenAt), latest(this.ips, this.devices));
        }

        static PlayerObservation empty(String id, String name) {
            return new PlayerObservation(id, name, List.of(), List.of(), 0);
        }

        PlayerObservation observe(String name, String ip, String device, long at) {
            List<SignalObservation> updatedIps = addSignal(ips, ip, at);
            List<SignalObservation> updatedDevices = ServerDeviceIdentity.valid(device)
                    ? addSignal(devices, device, at) : devices;
            return new PlayerObservation(playerId, name == null || name.isBlank() ? playerName : name,
                    updatedIps, updatedDevices, at);
        }

        PlayerObservation withSignals(List<SignalObservation> updatedIps,
                                      List<SignalObservation> updatedDevices) {
            return new PlayerObservation(playerId, playerName, updatedIps, updatedDevices,
                    latest(updatedIps, updatedDevices));
        }

        String playerId() { return playerId; }
        String playerName() { return playerName; }
        long lastSeenAt() { return lastSeenAt; }
        List<SignalObservation> ipSignals() { return ips; }
        List<SignalObservation> deviceSignals() { return devices; }
        List<String> ips() { return ips.stream().map(SignalObservation::value).toList(); }
        List<String> devices() { return devices.stream().map(SignalObservation::value).toList(); }

        private static long latest(List<SignalObservation> ips, List<SignalObservation> devices) {
            long latest = 0;
            for (SignalObservation signal : ips) latest = Math.max(latest, signal.lastSeenAt());
            for (SignalObservation signal : devices) latest = Math.max(latest, signal.lastSeenAt());
            return latest;
        }
    }

    record ModerationSignals(List<String> ips, List<String> devices, int staleIps, int staleDevices) {
        ModerationSignals {
            ips = ips == null ? List.of() : List.copyOf(ips);
            devices = devices == null ? List.of() : List.copyOf(devices);
            staleIps = Math.max(0, staleIps);
            staleDevices = Math.max(0, staleDevices);
        }

        static ModerationSignals empty() {
            return new ModerationSignals(List.of(), List.of(), 0, 0);
        }

        int staleCount() { return staleIps + staleDevices; }
    }

    record MutationResult(boolean success, boolean changed, String message) {
        MutationResult {
            message = message == null ? "" : message;
        }
    }

    private record PruneCounts(int signals, int players) {
        boolean changed() { return signals > 0 || players > 0; }
    }
}
