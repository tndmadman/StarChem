package com.tndmadman.rts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/** Durable lifecycle metadata for retained multiplayer identities; contains no authentication material. */
final class ServerIdentityStore {
    private static final int VERSION = 1;
    private static final int MAX_IDENTITIES = 10_000;

    private final Path path;
    private final Path previousPath;
    private final LongSupplier clock;
    private final LinkedHashMap<String,IdentityRecord> identities = new LinkedHashMap<>();
    private int nextPlayerNumber = 1;
    private String restrictedReason = "";
    private boolean failNextSaveForTest;

    ServerIdentityStore(Path saveDir, String saveName) {
        this(saveDir, saveName, System::currentTimeMillis);
    }

    ServerIdentityStore(Path saveDir, String saveName, LongSupplier clock) {
        Path dir = saveDir == null ? Path.of("saves") : saveDir;
        String cleanName = Config.cleanSaveName(saveName);
        path = dir.resolve(cleanName + "-identities.json");
        previousPath = dir.resolve(cleanName + "-identities-previous.json");
        this.clock = clock == null ? System::currentTimeMillis : clock;
        load();
    }

    synchronized MutationResult synchronize(List<PersistentPlayerSession> sessions) {
        if (restricted()) return MutationResult.failure(restrictedReason);
        LinkedHashMap<String,IdentityRecord> before = snapshotMap();
        int beforeNext = nextPlayerNumber;
        long now = now();
        LinkedHashSet<String> retained = new LinkedHashSet<>();
        if (sessions != null) {
            for (PersistentPlayerSession session : sessions) {
                if (session == null || session.playerId().isBlank()) continue;
                String key = key(session.playerId());
                retained.add(key);
                IdentityRecord current = identities.get(key);
                if (current == null) identities.put(key, new IdentityRecord(session.playerId(), session.name(), now, now, false));
                else if (!current.playerName().equals(Config.clean(session.name()))) identities.put(key, current.withName(session.name()));
                reserve(session.playerId());
            }
        }
        identities.entrySet().removeIf(entry -> !retained.contains(entry.getKey()));
        if (identities.equals(before) && nextPlayerNumber == beforeNext) return MutationResult.unchanged("Identity lifecycle state is synchronized.");
        return saveOrRollback(before, beforeNext, "Synchronized retained identity lifecycle state.");
    }

    synchronized MutationResult recordSeen(String playerId, String playerName) {
        if (playerId == null || playerId.isBlank()) return MutationResult.failure("Player identity is required.");
        if (restricted()) return MutationResult.failure(restrictedReason);
        LinkedHashMap<String,IdentityRecord> before = snapshotMap();
        int beforeNext = nextPlayerNumber;
        long now = now();
        String key = key(playerId);
        IdentityRecord current = identities.get(key);
        IdentityRecord updated = current == null
                ? new IdentityRecord(playerId, playerName, now, now, false)
                : current.withSeen(playerName, now);
        identities.put(key, updated);
        reserve(playerId);
        return saveOrRollback(before, beforeNext, current == null
                ? "Created lifecycle metadata for " + playerId + "."
                : "Updated last-seen time for " + playerId + ".");
    }

    synchronized MutationResult archive(String selector) {
        return setArchived(selector, true);
    }

    synchronized MutationResult restore(String selector) {
        return setArchived(selector, false);
    }

    synchronized MutationResult delete(String selector) {
        if (restricted()) return MutationResult.failure(restrictedReason);
        String selectedKey = findKey(selector);
        if (selectedKey == null) return MutationResult.failure("Unknown retained identity: " + selector);
        LinkedHashMap<String,IdentityRecord> before = snapshotMap();
        int beforeNext = nextPlayerNumber;
        IdentityRecord removed = identities.remove(selectedKey);
        reserve(removed.playerId());
        return saveOrRollback(before, beforeNext, "Deleted lifecycle metadata for " + removed.playerId() + ".");
    }

    synchronized IdentityRecord find(String selector) {
        String selectedKey = findKey(selector);
        return selectedKey == null ? null : identities.get(selectedKey);
    }

    synchronized List<IdentityRecord> snapshot() {
        ArrayList<IdentityRecord> out = new ArrayList<>(identities.values());
        out.sort(Comparator.comparingLong(IdentityRecord::lastSeenAt).reversed().thenComparing(IdentityRecord::playerId));
        return List.copyOf(out);
    }

    synchronized String denialReason(String playerId) {
        if (restricted()) return "Identity lifecycle state requires operator recovery: " + restrictedReason;
        IdentityRecord record = find(playerId);
        return record != null && record.archived() ? "Player identity is archived by the server operator." : "";
    }

    synchronized int nextPlayerNumber() { return Math.max(1, nextPlayerNumber); }
    synchronized boolean restricted() { return !restrictedReason.isBlank(); }
    synchronized String restrictedReason() { return restrictedReason; }
    synchronized Path pathForTest() { return path; }
    synchronized void failNextSaveForTest() { failNextSaveForTest = true; }

    private MutationResult setArchived(String selector, boolean archived) {
        if (restricted()) return MutationResult.failure(restrictedReason);
        String selectedKey = findKey(selector);
        if (selectedKey == null) return MutationResult.failure("Unknown retained identity: " + selector);
        IdentityRecord current = identities.get(selectedKey);
        if (current.archived() == archived) {
            return MutationResult.unchanged(current.playerId() + (archived ? " is already archived." : " is already active."));
        }
        LinkedHashMap<String,IdentityRecord> before = snapshotMap();
        int beforeNext = nextPlayerNumber;
        identities.put(selectedKey, current.withArchived(archived));
        return saveOrRollback(before, beforeNext, (archived ? "Archived " : "Restored ") + current.playerId() + ".");
    }

    private MutationResult saveOrRollback(LinkedHashMap<String,IdentityRecord> before, int beforeNext, String message) {
        try {
            save();
            return MutationResult.changed(message);
        } catch (IOException ex) {
            identities.clear();
            identities.putAll(before);
            nextPlayerNumber = beforeNext;
            return MutationResult.failure("Could not save identity lifecycle state: " + ex.getMessage());
        }
    }

    private void load() {
        if (!Files.exists(path) && !Files.exists(previousPath)) return;
        ArrayList<String> failures = new ArrayList<>();
        if (Files.isRegularFile(path)) {
            try {
                apply(parseState(Files.readString(path, StandardCharsets.UTF_8)));
                return;
            } catch (Exception ex) {
                failures.add("current: " + detail(ex));
            }
        } else failures.add("current: file is missing");
        if (Files.isRegularFile(previousPath)) {
            try {
                State recovered = parseState(Files.readString(previousPath, StandardCharsets.UTF_8));
                apply(recovered);
                CompanionStateFiles.repairCurrent(path, recovered, ServerIdentityStore::parseState, ServerIdentityStore::serializeState);
                return;
            } catch (Exception ex) {
                failures.add("previous: " + detail(ex));
            }
        } else failures.add("previous: file is missing");
        restrictedReason = "current and previous identity files were unavailable (" + String.join("; ", failures) + ")";
    }

    private void save() throws IOException {
        if (failNextSaveForTest) {
            failNextSaveForTest = false;
            throw new IOException("simulated identity lifecycle save failure");
        }
        State state = new State(nextPlayerNumber, List.copyOf(identities.values()));
        CompanionStateFiles.save(path, previousPath, state, ServerIdentityStore::parseState, ServerIdentityStore::serializeState);
    }

    private void apply(State state) {
        identities.clear();
        nextPlayerNumber = Math.max(1, state.nextPlayerNumber());
        for (IdentityRecord record : state.identities()) {
            identities.put(key(record.playerId()), record);
            reserve(record.playerId());
        }
    }

    private void reserve(String playerId) {
        if (playerId == null || playerId.length() < 2 || Character.toUpperCase(playerId.charAt(0)) != 'P') return;
        try { nextPlayerNumber = Math.max(nextPlayerNumber, Math.addExact(Integer.parseInt(playerId.substring(1)), 1)); }
        catch (NumberFormatException | ArithmeticException ignored) { }
    }

    private String findKey(String selector) {
        if (selector == null || selector.isBlank()) return null;
        String wanted = selector.trim();
        String direct = key(wanted);
        if (identities.containsKey(direct)) return direct;
        for (Map.Entry<String,IdentityRecord> entry : identities.entrySet()) {
            if (entry.getValue().playerName().equalsIgnoreCase(wanted)) return entry.getKey();
        }
        return null;
    }

    private LinkedHashMap<String,IdentityRecord> snapshotMap() { return new LinkedHashMap<>(identities); }
    private long now() { return Math.max(1, clock.getAsLong()); }
    private static String key(String playerId) { return playerId == null ? "" : playerId.trim().toLowerCase(Locale.ROOT); }

    private static State parseState(String text) throws IOException {
        Object parsed;
        try { parsed = MiniJson.parse(text); }
        catch (RuntimeException ex) { throw new IOException("could not parse identity JSON: " + ex.getMessage(), ex); }
        if (!(parsed instanceof Map<?,?> root)) throw new IOException("identity file root is not an object");
        int version = integer(root.get("version"), "version");
        if (version != VERSION) throw new IOException("unsupported identity file version " + version);
        int next = integer(root.get("nextPlayerNumber"), "nextPlayerNumber");
        if (next < 1) throw new IOException("nextPlayerNumber must be positive");
        Object rows = root.get("identities");
        if (!(rows instanceof List<?> list)) throw new IOException("identities is not an array");
        if (list.size() > MAX_IDENTITIES) throw new IOException("identity count exceeds " + MAX_IDENTITIES);
        ArrayList<IdentityRecord> identities = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Object value : list) {
            if (!(value instanceof Map<?,?> row)) throw new IOException("identity row is not an object");
            String playerId = string(row.get("playerId"), "playerId");
            if (playerId.isBlank() || !ids.add(key(playerId))) throw new IOException("identity IDs must be unique and nonblank");
            String playerName = string(row.get("playerName"), "playerName");
            long createdAt = wholeLong(row.get("createdAt"), "createdAt");
            long lastSeenAt = wholeLong(row.get("lastSeenAt"), "lastSeenAt");
            if (!(row.get("archived") instanceof Boolean archived)) throw new IOException("archived is not boolean");
            identities.add(new IdentityRecord(playerId, playerName, createdAt, lastSeenAt, archived));
        }
        return new State(next, List.copyOf(identities));
    }

    private static String serializeState(State state) {
        LinkedHashMap<String,Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        root.put("nextPlayerNumber", Math.max(1, state.nextPlayerNumber()));
        ArrayList<Object> rows = new ArrayList<>();
        for (IdentityRecord record : state.identities()) {
            LinkedHashMap<String,Object> row = new LinkedHashMap<>();
            row.put("playerId", record.playerId());
            row.put("playerName", record.playerName());
            row.put("createdAt", record.createdAt());
            row.put("lastSeenAt", record.lastSeenAt());
            row.put("archived", record.archived());
            rows.add(row);
        }
        root.put("identities", rows);
        root.put("updatedAt", Instant.now().toString());
        return MiniJson.stringify(root);
    }

    private static int integer(Object value, String field) throws IOException {
        long raw = wholeLong(value, field);
        if (raw < Integer.MIN_VALUE || raw > Integer.MAX_VALUE) throw new IOException(field + " is outside integer range");
        return (int)raw;
    }

    private static long wholeLong(Object value, String field) throws IOException {
        if (!(value instanceof Number number)) throw new IOException(field + " is not numeric");
        double decimal = number.doubleValue();
        long raw = number.longValue();
        if (!Double.isFinite(decimal) || decimal != raw || raw < 0) throw new IOException(field + " is not a nonnegative integer");
        return raw;
    }

    private static String string(Object value, String field) throws IOException {
        if (!(value instanceof String text)) throw new IOException(field + " is not text");
        return text;
    }

    private static String detail(Exception ex) {
        String message = ex == null ? "unknown failure" : ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    record IdentityRecord(String playerId, String playerName, long createdAt, long lastSeenAt, boolean archived) {
        IdentityRecord {
            playerId = playerId == null ? "" : playerId.trim();
            playerName = Config.clean(playerName);
            createdAt = Math.max(1, createdAt);
            lastSeenAt = Math.max(createdAt, lastSeenAt);
        }
        IdentityRecord withName(String name) { return new IdentityRecord(playerId, name, createdAt, lastSeenAt, archived); }
        IdentityRecord withSeen(String name, long seenAt) { return new IdentityRecord(playerId, name, createdAt, Math.max(lastSeenAt, seenAt), archived); }
        IdentityRecord withArchived(boolean value) { return new IdentityRecord(playerId, playerName, createdAt, lastSeenAt, value); }
    }

    record MutationResult(boolean success, boolean changed, String message) {
        MutationResult { message = message == null ? "" : message; }
        static MutationResult changed(String message) { return new MutationResult(true, true, message); }
        static MutationResult unchanged(String message) { return new MutationResult(true, false, message); }
        static MutationResult failure(String message) { return new MutationResult(false, false, message); }
    }

    private record State(int nextPlayerNumber, List<IdentityRecord> identities) {
        State { identities = identities == null ? List.of() : List.copyOf(identities); }
    }
}
