package com.tndmadman.rts;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Retains bounded last-seen moderation signals without authentication material. */
final class ServerPlayerObservationStore {
    private static final int MAX_PLAYERS = 10_000;
    private static final int MAX_SIGNALS_PER_PLAYER = 8;
    private final Path path;
    private final LinkedHashMap<String,PlayerObservation> observations = new LinkedHashMap<>();

    ServerPlayerObservationStore(Path saveDir, String saveName) {
        Path dir = saveDir == null ? Path.of("saves") : saveDir;
        path = dir.resolve(Config.cleanSaveName(saveName) + "-observations.json");
        load();
    }

    synchronized void record(String playerId, String playerName, InetAddress address, String deviceId) {
        if (playerId == null || playerId.isBlank()) return;
        String key = playerId.trim().toLowerCase(Locale.ROOT);
        PlayerObservation current = observations.get(key);
        PlayerObservation updated = (current == null ? PlayerObservation.empty(playerId, playerName) : current)
                .observe(playerName, address == null ? "" : address.getHostAddress(), deviceId, System.currentTimeMillis());
        observations.put(key, updated);
        trim();
        try { save(); } catch (IOException ex) { System.err.println("Could not save player observations: " + ex.getMessage()); }
    }

    synchronized PlayerObservation find(String selector) {
        if (selector == null || selector.isBlank()) return null;
        String wanted = selector.trim().toLowerCase(Locale.ROOT);
        PlayerObservation direct = observations.get(wanted);
        if (direct != null) return direct;
        for (PlayerObservation observation : observations.values()) {
            if (observation.playerId().equalsIgnoreCase(selector) || observation.playerName().equalsIgnoreCase(selector)) return observation;
        }
        return null;
    }

    synchronized List<String> lines(String selector) {
        ArrayList<PlayerObservation> rows = new ArrayList<>();
        if (selector == null || selector.isBlank()) rows.addAll(observations.values());
        else {
            PlayerObservation found = find(selector);
            if (found != null) rows.add(found);
        }
        rows.sort(Comparator.comparingLong(PlayerObservation::lastSeenAt).reversed());
        if (rows.isEmpty()) return List.of("No player observations matched.");
        ArrayList<String> lines = new ArrayList<>();
        for (PlayerObservation observation : rows) {
            lines.add(observation.playerId() + " | " + observation.playerName() + " | last seen "
                    + Instant.ofEpochMilli(observation.lastSeenAt()) + " | IPs "
                    + (observation.ips().isEmpty() ? "none" : String.join(",", observation.ips()))
                    + " | devices " + masked(observation.devices()));
            if (lines.size() >= 100) break;
        }
        return List.copyOf(lines);
    }

    private void load() {
        if (!Files.isRegularFile(path)) return;
        try {
            Object parsed = MiniJson.parse(Files.readString(path, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?,?> raw)) return;
            Object players = raw.get("players");
            if (!(players instanceof List<?> list)) return;
            for (Object value : list) {
                if (!(value instanceof Map<?,?> row)) continue;
                String playerId = text(row.get("playerId"));
                if (playerId.isBlank()) continue;
                PlayerObservation observation = new PlayerObservation(playerId, text(row.get("playerName")),
                        strings(row.get("ips")), strings(row.get("devices")), number(row.get("lastSeenAt")));
                observations.put(playerId.toLowerCase(Locale.ROOT), observation);
            }
            trim();
        } catch (Exception ex) {
            System.err.println("Could not load player observations: " + ex.getMessage());
        }
    }

    private void save() throws IOException {
        LinkedHashMap<String,Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        ArrayList<Object> players = new ArrayList<>();
        for (PlayerObservation observation : observations.values()) {
            LinkedHashMap<String,Object> row = new LinkedHashMap<>();
            row.put("playerId", observation.playerId());
            row.put("playerName", observation.playerName());
            row.put("ips", observation.ips());
            row.put("devices", observation.devices());
            row.put("lastSeenAt", observation.lastSeenAt());
            players.add(row);
        }
        root.put("players", players);
        root.put("updatedAt", Instant.now().toString());
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temp, MiniJson.stringify(root) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (IOException ex) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
    }

    private void trim() {
        if (observations.size() <= MAX_PLAYERS) return;
        ArrayList<PlayerObservation> sorted = new ArrayList<>(observations.values());
        sorted.sort(Comparator.comparingLong(PlayerObservation::lastSeenAt).reversed());
        observations.clear();
        for (int i = 0; i < Math.min(MAX_PLAYERS, sorted.size()); i++) {
            PlayerObservation observation = sorted.get(i);
            observations.put(observation.playerId().toLowerCase(Locale.ROOT), observation);
        }
    }

    private static String masked(List<String> devices) {
        if (devices == null || devices.isEmpty()) return "none";
        ArrayList<String> out = new ArrayList<>();
        for (String device : devices) out.add(ServerDeviceIdentity.mask(device));
        return String.join(",", out);
    }

    private static List<String> addSignal(List<String> existing, String value) {
        ArrayList<String> out = new ArrayList<>();
        if (value != null && !value.isBlank()) out.add(value.trim());
        if (existing != null) for (String item : existing) if (item != null && !item.isBlank() && !out.contains(item)) out.add(item);
        if (out.size() > MAX_SIGNALS_PER_PLAYER) return List.copyOf(out.subList(0, MAX_SIGNALS_PER_PLAYER));
        return List.copyOf(out);
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        ArrayList<String> out = new ArrayList<>();
        for (Object item : list) if (item != null && !String.valueOf(item).isBlank()) out.add(String.valueOf(item));
        return List.copyOf(out);
    }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static long number(Object value) { if (value instanceof Number n) return n.longValue(); try { return Long.parseLong(text(value)); } catch (NumberFormatException ex) { return 0; } }

    record PlayerObservation(String playerId, String playerName, List<String> ips, List<String> devices, long lastSeenAt) {
        PlayerObservation {
            playerId = playerId == null ? "" : playerId.trim();
            playerName = Config.clean(playerName);
            ips = ips == null ? List.of() : List.copyOf(ips);
            devices = devices == null ? List.of() : List.copyOf(devices);
            lastSeenAt = Math.max(0, lastSeenAt);
        }
        static PlayerObservation empty(String id, String name) { return new PlayerObservation(id, name, List.of(), List.of(), 0); }
        PlayerObservation observe(String name, String ip, String device, long at) {
            return new PlayerObservation(playerId, name == null || name.isBlank() ? playerName : name,
                    addSignal(ips, ip), ServerDeviceIdentity.valid(device) ? addSignal(devices, device) : devices, at);
        }
    }
}
