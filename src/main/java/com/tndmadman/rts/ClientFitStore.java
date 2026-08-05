package com.tndmadman.rts;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Durable, client-only player fit library, isolated by normalized commander name. */
final class ClientFitStore {
    private static final String STORE_OVERRIDE = "starchem.fitStore";
    private static final AtomicLong IDS = new AtomicLong();
    private static final MiniJson.Limits LIMITS = new MiniJson.Limits(
            2 * 1024 * 1024, 12, 100_000, 2_048, 65_536, 128, true);
    private static final Object CACHE_LOCK = new Object();

    private static Path cachedPath;
    private static StoreState cachedState;
    private static FileStamp cachedStamp = FileStamp.missing();
    private static Path preparedPath;
    private static long loadCountForTest;
    private static long securitySetupCountForTest;

    private ClientFitStore() { }

    static List<PrivateShipFit> fits(String commanderName, String hullId) {
        return read(state -> state.library(commanderName).fits.values().stream()
                .filter(fit -> hullId == null || hullId.isBlank() || hullId.equals(fit.spec().hullId()))
                .sorted(java.util.Comparator.comparing(PrivateShipFit::name, String.CASE_INSENSITIVE_ORDER))
                .toList());
    }

    static PrivateShipFit fit(String commanderName, String id) {
        return read(state -> state.library(commanderName).fits.get(id));
    }

    static PrivateShipFit save(String commanderName, String existingId, String name, ShipFitSpec spec) {
        PlayerFitRules.Validation validation = PlayerFitRules.validate(spec);
        if (!validation.valid()) throw new IllegalArgumentException(validation.reason());
        String cleanName = PlayerFitRules.cleanName(name);
        if (cleanName.isBlank()) throw new IllegalArgumentException("Fit name is required.");
        return update(state -> {
            Library library = state.library(commanderName);
            String id = existingId == null ? "" : existingId.trim();
            PrivateShipFit previous = library.fits.get(id);
            long now = System.currentTimeMillis();
            if (previous == null) {
                id = nextId();
                previous = new PrivateShipFit(id, cleanName, spec, now, now);
            } else {
                previous = new PrivateShipFit(id, cleanName, spec, previous.createdAt(), now);
            }
            library.fits.put(id, previous);
            return previous;
        });
    }

    static PrivateShipFit importPublished(String commanderName, PublishedFit published) {
        if (published == null || !published.valid()) throw new IllegalArgumentException("Published fit is invalid.");
        return save(commanderName, "", published.name() + " copy", published.spec());
    }

    static boolean delete(String commanderName, String id) {
        return update(state -> {
            Library library = state.library(commanderName);
            if (library.fits.remove(id) == null) return false;
            library.standardByHull.entrySet().removeIf(entry -> id.equals(entry.getValue()));
            return true;
        });
    }

    static void setStandard(String commanderName, String hullId, String privateFitId) {
        update(state -> {
            Library library = state.library(commanderName);
            if (privateFitId == null || privateFitId.isBlank()) {
                library.standardByHull.remove(hullId);
                return null;
            }
            PrivateShipFit fit = library.fits.get(privateFitId);
            if (fit == null || !fit.spec().hullId().equals(hullId)) {
                throw new IllegalArgumentException("The selected fit does not belong to this ship class.");
            }
            library.standardByHull.put(hullId, privateFitId);
            return null;
        });
    }

    static PrivateShipFit standard(String commanderName, String hullId) {
        return read(state -> {
            Library library = state.library(commanderName);
            String id = library.standardByHull.get(hullId);
            PrivateShipFit fit = id == null ? null : library.fits.get(id);
            if (id != null && fit == null) library.standardByHull.remove(hullId);
            return fit;
        });
    }

    static Path pathForTest() { return storePath(); }

    static long loadCountForTest() {
        synchronized (CACHE_LOCK) { return loadCountForTest; }
    }

    static long securitySetupCountForTest() {
        synchronized (CACHE_LOCK) { return securitySetupCountForTest; }
    }

    static void resetCacheForTest() {
        synchronized (CACHE_LOCK) {
            cachedPath = null;
            cachedState = null;
            cachedStamp = FileStamp.missing();
            preparedPath = null;
            loadCountForTest = 0;
            securitySetupCountForTest = 0;
        }
    }

    private static String nextId() {
        long value = System.currentTimeMillis() ^ IDS.incrementAndGet();
        return "local_" + Long.toUnsignedString(value, 36);
    }

    private static <T> T read(StateOperation<T> operation) {
        synchronized (CACHE_LOCK) {
            Path current = storePath();
            prepareStorage(current);
            refreshCache(current);
            return operation.apply(cachedState);
        }
    }

    private static <T> T update(StateOperation<T> operation) {
        synchronized (CACHE_LOCK) {
            Path current = storePath();
            prepareStorage(current);
            Path lockPath = lockPath(current);
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                StoreState state = load(current);
                loadCountForTest++;
                T result = operation.apply(state);
                persist(current, state);
                cachedPath = current;
                cachedState = state;
                cachedStamp = FileStamp.capture(current);
                return result;
            } catch (IOException ex) {
                throw new IllegalStateException("Could not access private fit library: " + current, ex);
            }
        }
    }

    private static void prepareStorage(Path current) {
        Path parent = current.getParent();
        if (parent == null) throw new IllegalStateException("Private fit library has no parent directory: " + current);
        Path lockPath = lockPath(current);
        if (current.equals(preparedPath) && Files.isDirectory(parent) && Files.isRegularFile(lockPath)) return;
        try {
            PrivateFileSecurity.ensurePrivateDirectory(parent);
            try { Files.createFile(lockPath); }
            catch (FileAlreadyExistsException ignored) { }
            PrivateFileSecurity.secureFile(lockPath);
            preparedPath = current;
            securitySetupCountForTest++;
        } catch (IOException ex) {
            throw new IllegalStateException("Could not prepare private fit library: " + current, ex);
        }
    }

    private static void refreshCache(Path current) {
        FileStamp stamp;
        try { stamp = FileStamp.capture(current); }
        catch (IOException ex) {
            throw new IllegalStateException("Could not inspect private fit library: " + current, ex);
        }
        if (current.equals(cachedPath) && cachedState != null && stamp.equals(cachedStamp)) return;
        cachedState = load(current);
        cachedPath = current;
        cachedStamp = stamp;
        loadCountForTest++;
    }

    private static StoreState load(Path current) {
        Path previous = previous(current);
        boolean currentExists = Files.isRegularFile(current);
        boolean previousExists = Files.isRegularFile(previous);
        RuntimeException currentFailure = null;
        if (currentExists) {
            try { return parse(read(current)); }
            catch (RuntimeException ex) { currentFailure = ex; }
        }
        RuntimeException previousFailure = null;
        if (previousExists) {
            try { return parse(read(previous)); }
            catch (RuntimeException ex) { previousFailure = ex; }
        }
        if (!currentExists && !previousExists) return new StoreState();

        RuntimeException primary = currentFailure != null ? currentFailure : previousFailure;
        String recovery = previousExists
                ? " Recovery copy is also invalid: " + previous + "."
                : " No recovery copy exists at " + previous + ".";
        IllegalStateException failure = new IllegalStateException(
                "Private fit library is corrupt and no valid recovery copy is available: " + current + "."
                        + recovery,
                primary);
        if (currentFailure != null && currentFailure != primary) failure.addSuppressed(currentFailure);
        if (previousFailure != null && previousFailure != primary) failure.addSuppressed(previousFailure);
        throw failure;
    }

    private static void persist(Path current, StoreState state) throws IOException {
        Path parent = current.getParent();
        Path previous = previous(current);
        Path temp = PrivateFileSecurity.createPrivateTempFile(parent, "fits-", ".tmp");
        Path previousTemp = null;
        try {
            String json = MiniJson.stringify(state.toMap()) + "\n";
            if (json.length() > LIMITS.maxDocumentChars()) throw new IOException("Private fit library exceeds its size limit.");
            Files.writeString(temp, json, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            parse(read(temp));

            boolean rotateCurrent = false;
            if (Files.isRegularFile(current)) {
                try {
                    parse(read(current));
                    rotateCurrent = true;
                } catch (RuntimeException ignored) {
                    // load() already recovered from the verified previous copy. Do not replace it with corrupt data.
                }
            }
            if (rotateCurrent) {
                previousTemp = PrivateFileSecurity.createPrivateTempFile(parent, "fits-previous-", ".tmp");
                Files.copy(current, previousTemp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                parse(read(previousTemp));
                PrivateFileSecurity.moveReplace(previousTemp, previous);
                previousTemp = null;
                PrivateFileSecurity.secureFile(previous);
            }
            PrivateFileSecurity.moveReplace(temp, current);
            PrivateFileSecurity.secureFile(current);
        } finally {
            Files.deleteIfExists(temp);
            if (previousTemp != null) Files.deleteIfExists(previousTemp);
        }
    }

    private static String read(Path path) {
        try {
            PrivateFileSecurity.verifyPrivateRegularFile(path);
            long size = Files.size(path);
            if (size > 2L * 1024 * 1024) throw new IllegalArgumentException("Private fit library is too large.");
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not read private fit library.", ex);
        }
    }

    private static StoreState parse(String text) {
        Object parsed = MiniJson.parse(text == null ? "{}" : text, LIMITS);
        Map<String,Object> root = ServerSaveStore.object(parsed);
        StoreState state = new StoreState();
        Map<String,Object> commanders = ServerSaveStore.object(root.get("commanders"));
        for (Map.Entry<String,Object> entry : commanders.entrySet()) {
            Map<String,Object> row = ServerSaveStore.object(entry.getValue());
            Library library = new Library(entry.getKey(), ServerSaveStore.string(row, "displayName", entry.getKey()));
            for (Object fitValue : ServerSaveStore.list(row.get("fits"))) {
                PrivateShipFit fit = PrivateShipFit.from(fitValue);
                if (fit.valid()) library.fits.put(fit.id(), fit);
            }
            for (Map.Entry<String,Object> standard : ServerSaveStore.object(row.get("standards")).entrySet()) {
                String id = String.valueOf(standard.getValue());
                PrivateShipFit fit = library.fits.get(id);
                if (fit != null && standard.getKey().equals(fit.spec().hullId())) library.standardByHull.put(standard.getKey(), id);
            }
            state.commanders.put(entry.getKey(), library);
        }
        return state;
    }

    private static Path storePath() {
        String override = System.getProperty(STORE_OVERRIDE, "").trim();
        Path path = override.isBlank()
                ? Path.of(System.getProperty("user.home", "."), ".starchem", "fits.json")
                : Path.of(override);
        return path.toAbsolutePath().normalize();
    }

    private static Path previous(Path current) { return current.resolveSibling(current.getFileName() + ".previous"); }
    private static Path lockPath(Path current) { return current.resolveSibling(current.getFileName() + ".lock"); }

    private static String commanderKey(String commanderName) {
        return Config.clean(commanderName == null ? "" : commanderName).toLowerCase(Locale.ROOT);
    }

    private interface StateOperation<T> { T apply(StoreState state); }

    private record FilePart(boolean exists, long size, long modifiedMillis) {
        static FilePart capture(Path path) throws IOException {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return missing();
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) return new FilePart(true, -1, attributes.lastModifiedTime().toMillis());
            return new FilePart(true, attributes.size(), attributes.lastModifiedTime().toMillis());
        }

        static FilePart missing() { return new FilePart(false, 0, 0); }
    }

    private record FileStamp(FilePart current, FilePart previous) {
        static FileStamp capture(Path current) throws IOException {
            return new FileStamp(FilePart.capture(current), FilePart.capture(ClientFitStore.previous(current)));
        }

        static FileStamp missing() { return new FileStamp(FilePart.missing(), FilePart.missing()); }
    }

    private static final class StoreState {
        final Map<String,Library> commanders = new LinkedHashMap<>();

        Library library(String commanderName) {
            String key = commanderKey(commanderName);
            if (key.isBlank()) key = "commander";
            String display = Config.clean(commanderName);
            String finalKey = key;
            return commanders.computeIfAbsent(key, ignored -> new Library(finalKey, display));
        }

        Map<String,Object> toMap() {
            Map<String,Object> root = new LinkedHashMap<>();
            root.put("schemaVersion", 1);
            Map<String,Object> rows = new LinkedHashMap<>();
            for (Map.Entry<String,Library> entry : commanders.entrySet()) rows.put(entry.getKey(), entry.getValue().toMap());
            root.put("commanders", rows);
            return root;
        }
    }

    private static final class Library {
        final String key;
        final String displayName;
        final Map<String,PrivateShipFit> fits = new LinkedHashMap<>();
        final Map<String,String> standardByHull = new LinkedHashMap<>();

        Library(String key, String displayName) {
            this.key = key;
            this.displayName = displayName == null || displayName.isBlank() ? key : Config.clean(displayName);
        }

        Map<String,Object> toMap() {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("displayName", displayName);
            row.put("standards", new LinkedHashMap<>(standardByHull));
            List<Object> list = new ArrayList<>();
            for (PrivateShipFit fit : fits.values()) list.add(fit.toMap());
            row.put("fits", list);
            return row;
        }
    }
}

record PrivateShipFit(String id, String name, ShipFitSpec spec, long createdAt, long updatedAt) {
    PrivateShipFit {
        id = id == null ? "" : id.trim();
        name = PlayerFitRules.cleanName(name);
        spec = spec == null ? new ShipFitSpec("", List.of()) : spec;
        createdAt = Math.max(0, createdAt);
        updatedAt = Math.max(createdAt, updatedAt);
    }

    boolean valid() { return !id.isBlank() && !name.isBlank() && PlayerFitRules.validate(spec).valid(); }

    Map<String,Object> toMap() {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        row.put("spec", spec.toMap());
        row.put("createdAt", createdAt);
        row.put("updatedAt", updatedAt);
        return row;
    }

    static PrivateShipFit from(Object value) {
        Map<String,Object> row = ServerSaveStore.object(value);
        return new PrivateShipFit(ServerSaveStore.string(row, "id", ""),
                ServerSaveStore.string(row, "name", ""), ShipFitSpec.from(row.get("spec")),
                ServerSaveStore.longValue(row, "createdAt", 0), ServerSaveStore.longValue(row, "updatedAt", 0));
    }
}
