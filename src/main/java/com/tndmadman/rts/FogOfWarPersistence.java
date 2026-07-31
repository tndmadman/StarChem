package com.tndmadman.rts;

import java.awt.GraphicsEnvironment;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Bounded, endpoint-isolated client persistence for explored tactical fog and known wormholes. */
final class FogOfWarPersistence {
    private static final String PREFIX = "fow-v1.";
    private static final int MAX_VALUE_LENGTH = 2 * 1024 * 1024;
    private static final int MAX_WORMHOLES = 512;
    private static final long WRITE_DELAY_MS = 750;
    private static final ScheduledExecutorService WRITER = Executors.newSingleThreadScheduledExecutor(
            new DaemonThreadFactory());
    private static final Map<String, Pending> PENDING = new ConcurrentHashMap<>();
    private static final Map<String, ScheduledFuture<?>> SCHEDULED = new ConcurrentHashMap<>();
    private static volatile boolean testEnabled;

    record Stored(BitSet explored, List<FogOfWarView.KnownWormhole> wormholes) {
        Stored {
            explored = explored == null ? new BitSet() : (BitSet)explored.clone();
            wormholes = wormholes == null ? List.of() : List.copyOf(wormholes);
        }
    }

    private FogOfWarPersistence() { }

    static Stored load(String playerId, String systemId, long environmentSeed, int columns, int rows) {
        if (!enabled()) return new Stored(new BitSet(), List.of());
        String key = storageKey(playerId, systemId, environmentSeed, columns, rows);
        if (key.isBlank()) return new Stored(new BitSet(), List.of());
        return ClientSessionPropertiesStore.read(properties -> decode(
                properties.getProperty(key, ""), columns, rows));
    }

    static void saveLater(String playerId, String systemId, long environmentSeed, int columns, int rows,
                          BitSet explored, Iterable<FogOfWarView.KnownWormhole> wormholes) {
        if (!enabled()) return;
        String key = storageKey(playerId, systemId, environmentSeed, columns, rows);
        if (key.isBlank() || explored == null) return;
        Pending snapshot = new Pending(columns, rows, (BitSet)explored.clone(), copyWormholes(wormholes));
        PENDING.put(key, snapshot);
        ScheduledFuture<?> previous = SCHEDULED.put(key,
                WRITER.schedule(() -> flushKey(key), WRITE_DELAY_MS, TimeUnit.MILLISECONDS));
        if (previous != null) previous.cancel(false);
    }

    static void flushForTest() {
        for (String key : List.copyOf(PENDING.keySet())) flushKey(key);
    }

    static void clearForTest(String playerId, String systemId, long environmentSeed, int columns, int rows) {
        testEnabled = true;
        String key = storageKey(playerId, systemId, environmentSeed, columns, rows);
        if (key.isBlank()) return;
        PENDING.remove(key);
        ScheduledFuture<?> future = SCHEDULED.remove(key);
        if (future != null) future.cancel(false);
        ClientSessionPropertiesStore.update(properties -> {
            properties.remove(key);
            return null;
        });
    }

    private static boolean enabled() {
        return testEnabled || !GraphicsEnvironment.isHeadless()
                || Boolean.getBoolean("starchem.fowPersistenceHeadless");
    }

    private static void flushKey(String key) {
        Pending pending = PENDING.remove(key);
        SCHEDULED.remove(key);
        if (pending == null) return;
        String encoded = encode(pending);
        if (encoded.isBlank() || encoded.length() > MAX_VALUE_LENGTH) return;
        ClientSessionPropertiesStore.update(properties -> {
            properties.setProperty(key, encoded);
            return null;
        });
    }

    private static String encode(Pending pending) {
        String bits = Base64.getUrlEncoder().withoutPadding().encodeToString(pending.explored().toByteArray());
        StringBuilder wormholes = new StringBuilder();
        List<FogOfWarView.KnownWormhole> sorted = new ArrayList<>(pending.wormholes());
        sorted.sort(Comparator.comparing(FogOfWarView.KnownWormhole::id));
        int count = 0;
        for (FogOfWarView.KnownWormhole gate : sorted) {
            if (gate == null || count >= MAX_WORMHOLES) break;
            String id = encodeText(gate.id());
            String target = encodeText(gate.toSystemId());
            if (id.isBlank() || target.isBlank() || !Double.isFinite(gate.x()) || !Double.isFinite(gate.y())) continue;
            if (!wormholes.isEmpty()) wormholes.append(';');
            wormholes.append(id).append(',').append(target).append(',')
                    .append(Double.toHexString(gate.x())).append(',').append(Double.toHexString(gate.y()));
            count++;
        }
        return pending.columns() + "|" + pending.rows() + "|" + bits + "|" + wormholes;
    }

    private static Stored decode(String raw, int columns, int rows) {
        if (raw == null || raw.isBlank() || raw.length() > MAX_VALUE_LENGTH) {
            return new Stored(new BitSet(), List.of());
        }
        String[] parts = raw.split("\\|", 4);
        if (parts.length != 4) return new Stored(new BitSet(), List.of());
        int storedColumns;
        int storedRows;
        try {
            storedColumns = Integer.parseInt(parts[0]);
            storedRows = Integer.parseInt(parts[1]);
        } catch (NumberFormatException ex) {
            return new Stored(new BitSet(), List.of());
        }
        if (storedColumns != columns || storedRows != rows || columns <= 0 || rows <= 0) {
            return new Stored(new BitSet(), List.of());
        }

        BitSet explored;
        try {
            byte[] bytes = parts[2].isBlank() ? new byte[0] : Base64.getUrlDecoder().decode(parts[2]);
            int maxBytes = Math.max(1, (columns * rows + 7) / 8);
            if (bytes.length > maxBytes) return new Stored(new BitSet(), List.of());
            explored = BitSet.valueOf(bytes);
            explored.clear(columns * rows, Math.max(columns * rows, explored.length()));
        } catch (IllegalArgumentException ex) {
            return new Stored(new BitSet(), List.of());
        }

        Map<String, FogOfWarView.KnownWormhole> wormholes = new LinkedHashMap<>();
        if (!parts[3].isBlank()) {
            for (String item : parts[3].split(";", -1)) {
                if (wormholes.size() >= MAX_WORMHOLES) break;
                String[] fields = item.split(",", -1);
                if (fields.length != 4) continue;
                String id = decodeText(fields[0]);
                String target = decodeText(fields[1]);
                if (id.isBlank() || target.isBlank()) continue;
                try {
                    double x = Double.valueOf(fields[2]);
                    double y = Double.valueOf(fields[3]);
                    if (!Double.isFinite(x) || !Double.isFinite(y)) continue;
                    wormholes.put(id, new FogOfWarView.KnownWormhole(id, target, x, y));
                } catch (NumberFormatException ignored) { }
            }
        }
        return new Stored(explored, List.copyOf(wormholes.values()));
    }

    private static List<FogOfWarView.KnownWormhole> copyWormholes(
            Iterable<FogOfWarView.KnownWormhole> source) {
        if (source == null) return List.of();
        List<FogOfWarView.KnownWormhole> copy = new ArrayList<>();
        for (FogOfWarView.KnownWormhole gate : source) {
            if (gate == null || copy.size() >= MAX_WORMHOLES) break;
            copy.add(gate);
        }
        return List.copyOf(copy);
    }

    private static String storageKey(String playerId, String systemId, long environmentSeed,
                                     int columns, int rows) {
        String player = clean(playerId, 64);
        String system = clean(systemId, 256);
        if (player.isBlank() || system.isBlank() || columns <= 0 || rows <= 0) return "";
        byte[] playerBytes = player.getBytes(StandardCharsets.UTF_8);
        byte[] systemBytes = system.getBytes(StandardCharsets.UTF_8);
        ByteBuffer input = ByteBuffer.allocate(playerBytes.length + systemBytes.length + 24);
        input.putInt(playerBytes.length).put(playerBytes);
        input.putInt(systemBytes.length).put(systemBytes);
        input.putLong(environmentSeed).putInt(columns).putInt(rows);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.array());
            return PREFIX + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

    private static String encodeText(String value) {
        String clean = clean(value, 256);
        return clean.isBlank() ? "" : Base64.getUrlEncoder().withoutPadding()
                .encodeToString(clean.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeText(String value) {
        if (value == null || value.isBlank() || value.length() > 1_024) return "";
        try {
            return clean(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8), 256);
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String clean = value.replace("|", "").replace("\n", "").replace("\r", "").trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private record Pending(int columns, int rows, BitSet explored,
                           List<FogOfWarView.KnownWormhole> wormholes) { }

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "starchem-fow-persistence");
            thread.setDaemon(true);
            return thread;
        }
    }

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(FogOfWarPersistence::flushForTest,
                "starchem-fow-persistence-shutdown"));
    }
}
