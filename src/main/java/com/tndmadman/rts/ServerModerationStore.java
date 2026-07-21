package com.tndmadman.rts;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class ServerModerationStore {
    private final Path path;
    private final Path previousPath;
    private CompanionLoadStatus loadStatus = CompanionLoadStatus.current("not loaded");

    ServerModerationStore(Path saveDir, String saveName) {
        Path dir = saveDir == null ? Path.of("saves") : saveDir;
        String cleanName = Config.cleanSaveName(saveName);
        path = dir.resolve(cleanName + "-moderation.json");
        previousPath = dir.resolve(cleanName + "-moderation-previous.json");
        CompanionRecoveryRegistry.configure(dir, cleanName);
    }

    Path path() { return path; }
    Path previousPath() { return previousPath; }
    CompanionLoadStatus loadStatus() { return loadStatus; }

    ServerModerationState load() {
        CompanionLoad<ServerModerationState> loaded = CompanionStateFiles.load(path, previousPath,
                "Moderation", ServerModerationStore::parseState, detail -> ServerModerationState.open());
        ServerModerationState state = loaded.value();
        loadStatus = loaded.status();
        boolean ready = !loadStatus.restricted();

        try {
            CompanionStateFiles.repairCurrent(path, state, ServerModerationStore::parseState,
                    ServerModerationStore::serializeState);
            if (loadStatus.restricted()) ready = true;
        } catch (IOException ex) {
            if (loadStatus.recoveredPrevious()) {
                loadStatus = CompanionLoadStatus.previous(loadStatus.detail()
                        + "; current repair failed: " + ex.getMessage());
            } else if (loadStatus.restricted()) {
                System.err.println("Could not seed restricted server moderation settings: " + ex.getMessage());
            } else {
                System.err.println("Could not persist normalized server moderation settings: " + ex.getMessage());
            }
        }

        CompanionRecoveryRegistry.recordModeration(loadStatus, ready);
        if (loadStatus.recoveredPrevious() || loadStatus.restricted()) {
            System.err.println(loadStatus.summary("Server moderation"));
        }
        return state;
    }

    synchronized void save(ServerModerationState state) throws IOException {
        ServerModerationState safe = state == null ? ServerModerationState.open()
                : state.activeOnly(System.currentTimeMillis());
        CompanionStateFiles.save(path, previousPath, safe, ServerModerationStore::parseState,
                ServerModerationStore::serializeState);
        loadStatus = CompanionLoadStatus.current("verified save");
        CompanionRecoveryRegistry.recordModeration(loadStatus, true);
    }

    private static ServerModerationState parseState(String text) throws IOException {
        Object parsed;
        try { parsed = MiniJson.parse(text); }
        catch (RuntimeException ex) { throw new IOException("could not parse moderation JSON: " + ex.getMessage(), ex); }
        if (!(parsed instanceof Map<?,?> raw)) throw new IOException("moderation file root is not an object");
        Map<String,Object> map = map(raw);
        Object version = map.get("version");
        if (version != null && !(version instanceof Number)) throw new IOException("moderation version is not numeric");
        boolean whitelistEnabled = requiredBoolean(map, "whitelistEnabled");
        Object whitelistValue = map.get("whitelist");
        if (!(whitelistValue instanceof List<?> whitelistList)) throw new IOException("moderation whitelist is not a list");
        LinkedHashSet<String> whitelist = new LinkedHashSet<>();
        for (Object value : whitelistList) {
            if (!(value instanceof String item)) throw new IOException("moderation whitelist entry is not text");
            String normalized = item.trim().toLowerCase(Locale.ROOT);
            if (!(normalized.startsWith("p:") || normalized.startsWith("n:")) || normalized.length() <= 2) {
                throw new IOException("moderation whitelist entry is invalid");
            }
            whitelist.add(normalized);
        }

        Object entriesValue = map.get("entries");
        if (!(entriesValue instanceof List<?> entryList)) throw new IOException("moderation entries is not a list");
        ArrayList<ModerationEntry> entries = new ArrayList<>();
        for (Object value : entryList) {
            if (!(value instanceof Map<?,?> entryRaw)) throw new IOException("moderation entry is not an object");
            Map<String,Object> entry = map(entryRaw);
            String kindText = requiredString(entry, "kind");
            ModerationKind kind;
            try { kind = ModerationKind.valueOf(kindText.toUpperCase(Locale.ROOT)); }
            catch (RuntimeException ex) { throw new IOException("moderation entry kind is invalid", ex); }
            entries.add(new ModerationEntry(requiredString(entry, "id"), kind,
                    requiredString(entry, "playerId"), requiredString(entry, "playerName"),
                    requiredString(entry, "target"), requiredLong(entry, "createdAt"),
                    requiredLong(entry, "expiresAt"), requiredString(entry, "reason")));
        }
        return new ServerModerationState(whitelistEnabled, whitelist, entries)
                .activeOnly(System.currentTimeMillis());
    }

    private static String serializeState(ServerModerationState state) {
        ServerModerationState safe = state == null ? ServerModerationState.open()
                : state.activeOnly(System.currentTimeMillis());
        LinkedHashMap<String,Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        root.put("whitelistEnabled", safe.whitelistEnabled());
        root.put("whitelist", new ArrayList<>(safe.whitelist()));
        ArrayList<Object> entries = new ArrayList<>();
        for (ModerationEntry entry : safe.entries()) {
            LinkedHashMap<String,Object> row = new LinkedHashMap<>();
            row.put("id", entry.id());
            row.put("kind", entry.kind().name());
            row.put("playerId", entry.playerId());
            row.put("playerName", entry.playerName());
            row.put("target", entry.target());
            row.put("createdAt", entry.createdAt());
            row.put("expiresAt", entry.expiresAt());
            row.put("reason", entry.reason());
            entries.add(row);
        }
        root.put("entries", entries);
        root.put("updatedAt", Instant.now().toString());
        return MiniJson.stringify(root);
    }

    private static Map<String,Object> map(Map<?,?> source) {
        LinkedHashMap<String,Object> out = new LinkedHashMap<>();
        for (Map.Entry<?,?> entry : source.entrySet()) out.put(String.valueOf(entry.getKey()), entry.getValue());
        return out;
    }

    private static boolean requiredBoolean(Map<String,Object> map, String key) throws IOException {
        Object value = map.get(key);
        if (!(value instanceof Boolean bool)) throw new IOException("moderation " + key + " is not boolean");
        return bool;
    }

    private static long requiredLong(Map<String,Object> map, String key) throws IOException {
        Object value = map.get(key);
        if (!(value instanceof Number number)) throw new IOException("moderation " + key + " is not numeric");
        long raw = number.longValue();
        if (raw != number.doubleValue()) throw new IOException("moderation " + key + " is not an integer");
        return raw;
    }

    private static String requiredString(Map<String,Object> map, String key) throws IOException {
        Object value = map.get(key);
        if (!(value instanceof String text)) throw new IOException("moderation " + key + " is not text");
        return text;
    }
}

final class ServerEventJournal {
    private static final int MAX_EVENTS = 500;
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);
    private final Deque<ServerEvent> events = new ArrayDeque<>();
    private final Path path;

    ServerEventJournal() { this.path = null; }

    ServerEventJournal(Path saveDir, String saveName) {
        Path dir = saveDir == null ? Path.of("saves") : saveDir;
        this.path = dir.resolve(Config.cleanSaveName(saveName) + "-activity.log");
        load();
    }

    synchronized void add(String type, String subject, String detail) {
        ServerEvent event = new ServerEvent(System.currentTimeMillis(), safe(type, 32), safe(subject, 128), safe(detail, 512));
        events.addLast(event);
        while (events.size() > MAX_EVENTS) events.removeFirst();
        append(event);
    }

    synchronized List<String> lines(int limit, String type, String subject) {
        ArrayList<ServerEvent> snapshot = new ArrayList<>(events);
        ArrayList<String> out = new ArrayList<>();
        String wantedType = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        String wantedSubject = subject == null ? "" : subject.trim().toLowerCase(Locale.ROOT);
        for (int i = snapshot.size() - 1; i >= 0 && out.size() < Math.max(1, Math.min(500, limit)); i--) {
            ServerEvent event = snapshot.get(i);
            if (!wantedType.isBlank() && !event.type().toLowerCase(Locale.ROOT).contains(wantedType)) continue;
            if (!wantedSubject.isBlank() && !(event.subject() + " " + event.detail()).toLowerCase(Locale.ROOT).contains(wantedSubject)) continue;
            out.add(format(event));
        }
        return out.isEmpty() ? List.of("No activity matched that filter.") : List.copyOf(out);
    }

    synchronized void export(Path target) throws IOException {
        if (target == null) throw new IOException("export path is missing");
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        ArrayList<String> rows = new ArrayList<>();
        for (ServerEvent event : events) rows.add(format(event));
        Files.write(target, rows, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    synchronized void clear() {
        events.clear();
        if (path != null) try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }

    private void load() {
        if (path == null || !Files.isRegularFile(path)) return;
        try {
            List<String> rows = Files.readAllLines(path, StandardCharsets.UTF_8);
            int start = Math.max(0, rows.size() - MAX_EVENTS);
            for (int i = start; i < rows.size(); i++) {
                String[] parts = rows.get(i).split("\\t", 4);
                if (parts.length != 4) continue;
                long at;
                try { at = Long.parseLong(parts[0]); } catch (NumberFormatException ex) { continue; }
                events.addLast(new ServerEvent(at, decode(parts[1]), decode(parts[2]), decode(parts[3])));
            }
        } catch (IOException ex) {
            System.err.println("Could not load server activity journal: " + ex.getMessage());
        }
    }

    private void append(ServerEvent event) {
        if (path == null) return;
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            if (Files.isRegularFile(path) && Files.size(path) > MAX_FILE_BYTES) {
                rewrite();
                return;
            }
            String row = event.at() + "\t" + encode(event.type()) + "\t" + encode(event.subject()) + "\t" + encode(event.detail()) + "\n";
            Files.writeString(path, row, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException ex) {
            System.err.println("Could not append server activity journal: " + ex.getMessage());
        }
    }

    private void rewrite() throws IOException {
        ArrayList<String> rows = new ArrayList<>();
        for (ServerEvent event : events) rows.add(event.at() + "\t" + encode(event.type()) + "\t" + encode(event.subject()) + "\t" + encode(event.detail()));
        Files.write(path, rows, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static String format(ServerEvent event) {
        return TIME.format(Instant.ofEpochMilli(event.at())) + " | " + event.type() + " | "
                + (event.subject().isBlank() ? "server" : event.subject())
                + (event.detail().isBlank() ? "" : " | " + event.detail());
    }

    private static String encode(String value) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        try { return new String(java.util.Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException ex) { return ""; }
    }

    private static String safe(String value, int max) {
        String clean = ServerModeration.clean(value);
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private record ServerEvent(long at, String type, String subject, String detail) { }
}

final class ServerDeviceIdentity {
    private ServerDeviceIdentity() { }

    static boolean valid(String value) {
        if (value == null || value.length() < 16 || value.length() > 128) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '-' && c != '_' && c != '.' && c != '~') return false;
        }
        return true;
    }

    static boolean equal(String left, String right) {
        return valid(left) && valid(right) && left.equals(right);
    }

    static String mask(String value) {
        if (!valid(value)) return "unavailable";
        return value.length() <= 12 ? value : value.substring(0, 6) + "..." + value.substring(value.length() - 6);
    }
}

final class IpBanMatcher {
    private IpBanMatcher() { }

    static String normalize(String value) {
        if (value == null || value.isBlank()) return "";
        String raw = value.trim();
        int slash = raw.indexOf('/');
        String addressPart = slash < 0 ? raw : raw.substring(0, slash);
        if (!numericAddress(addressPart)) return "";
        try {
            InetAddress address = InetAddress.getByName(addressPart);
            if (slash < 0) return address.getHostAddress();
            int prefix = Integer.parseInt(raw.substring(slash + 1));
            int bits = address.getAddress().length * 8;
            if (prefix < 0 || prefix > bits) return "";
            return address.getHostAddress() + "/" + prefix;
        } catch (Exception ex) {
            return "";
        }
    }

    static boolean matches(String rule, InetAddress address) {
        if (address == null) return false;
        String normalized = normalize(rule);
        if (normalized.isBlank()) return false;
        int slash = normalized.indexOf('/');
        try {
            InetAddress network = InetAddress.getByName(slash < 0 ? normalized : normalized.substring(0, slash));
            byte[] expected = network.getAddress();
            byte[] actual = address.getAddress();
            if (expected.length != actual.length) return false;
            int prefix = slash < 0 ? expected.length * 8 : Integer.parseInt(normalized.substring(slash + 1));
            for (int bit = 0; bit < prefix; bit++) {
                int mask = 1 << (7 - bit % 8);
                if ((expected[bit / 8] & mask) != (actual[bit / 8] & mask)) return false;
            }
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private static boolean numericAddress(String value) {
        if (value == null || value.isBlank()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(Character.isDigit(c) || c == '.' || c == ':' || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
        }
        return true;
    }
}
