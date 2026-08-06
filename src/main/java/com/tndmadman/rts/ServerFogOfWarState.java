package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Server-owned, per-player tactical exploration with a client-cache bootstrap wire format. */
final class ServerFogOfWarState {
    private static final int FORMAT_VERSION = 1;
    private static final int CELL_SIZE = FogOfWarView.CELL_SIZE;
    private static final int MAX_PLAYERS = 4_096;
    private static final int MAX_SYSTEMS_PER_PLAYER = 4_096;
    private static final int MAX_CELLS = 16_777_216;
    private static final int MAX_WORMHOLES = 512;
    private static final int MAX_PAYLOAD_LENGTH = 2 * 1024 * 1024;
    private static final long WRITE_DELAY_MS = 750;
    private static final Map<World, RuntimeState> STATES = new WeakHashMap<>();
    private static final Map<World, Stored> CLIENT_PENDING = new WeakHashMap<>();
    private static final ScheduledExecutorService WRITER = Executors.newSingleThreadScheduledExecutor(
            new DaemonThreadFactory());

    private ServerFogOfWarState() { }

    static void observeSystem(World world, String playerId, String systemId) {
        if (world == null || !realPlayerId(playerId) || invalidSystemId(systemId)) return;
        String previous = world.activeSystemId();
        if (systemId.equals(previous)) {
            observeActive(world, playerId);
            return;
        }
        try {
            world.activateSystem(systemId);
            if (!systemId.equals(world.activeSystemId())) return;
            observeActive(world, playerId);
        } finally {
            if (!invalidSystemId(previous) && !previous.equals(world.activeSystemId())) world.activateSystem(previous);
        }
    }

    static String packet(World world, String playerId, String systemId) {
        if (world == null || !realPlayerId(playerId) || invalidSystemId(systemId)) return "";
        RuntimeState runtime = runtime(world);
        Stored stored = null;
        synchronized (ServerFogOfWarState.class) {
            long generation = generation(world, systemId);
            for (Stored candidate : runtime.states.values()) {
                if (candidate.playerId().equals(playerId) && candidate.systemId().equals(systemId)
                        && candidate.generation() == generation) {
                    stored = candidate.copy();
                    break;
                }
            }
            if (stored == null) return "";
        }
        String encoded = encode(stored);
        return encoded.isBlank() ? "" : "FOG_STATE|" + encoded;
    }

    static void applyClient(World world, String playerId, String encoded) {
        if (world == null || !realPlayerId(playerId) || encoded == null || encoded.isBlank()) return;
        Stored stored = decode(encoded);
        if (stored == null || !playerId.equals(stored.playerId())) return;
        synchronized (ServerFogOfWarState.class) {
            if (!stored.systemId().equals(world.activeSystemId())) {
                CLIENT_PENDING.put(world, stored.copy());
                return;
            }
        }
        applyDecodedClient(world, playerId, stored);
    }

    static void applyPendingClient(World world, String playerId) {
        if (world == null || !realPlayerId(playerId)) return;
        Stored stored;
        synchronized (ServerFogOfWarState.class) {
            stored = CLIENT_PENDING.get(world);
            if (stored == null || !playerId.equals(stored.playerId())
                    || !world.activeSystemId().equals(stored.systemId())) return;
            CLIENT_PENDING.remove(world);
        }
        applyDecodedClient(world, playerId, stored);
    }

    private static void applyDecodedClient(World world, String playerId, Stored stored) {
        int columns = columns(world);
        int rows = rows(world);
        if (stored.columns() != columns || stored.rows() != rows) return;
        FogOfWarPersistence.saveLater(playerId, stored.systemId(), world.systemSeed(), columns, rows,
                stored.explored(), stored.wormholes());
        FogOfWarPersistence.flushForTest();
        FogOfWarView.clearCachedStateForTest(world);
    }

    static synchronized void flushForTest(World world) {
        RuntimeState runtime = STATES.get(world);
        if (runtime != null) flush(world, runtime);
    }

    static synchronized void configureForTest(World world, ServerFogOfWarStore store) {
        if (world == null) return;
        RuntimeState previous = STATES.remove(world);
        if (previous != null && previous.pending != null) previous.pending.cancel(false);
        STATES.put(world, load(store == null ? ServerFogOfWarStore.disabled() : store));
    }

    static synchronized int exploredCellCountForTest(World world, String playerId, String systemId) {
        RuntimeState runtime = runtime(world);
        Stored stored = runtime.states.get(new Key(playerId, systemId,
                generation(world, systemId), columns(world), rows(world)));
        return stored == null ? 0 : stored.explored().cardinality();
    }

    private static void observeActive(World world, String playerId) {
        String systemId = world.activeSystemId();
        if (invalidSystemId(systemId)) return;
        int columns = columns(world);
        int rows = rows(world);
        if ((long)columns * rows > MAX_CELLS) return;
        Key key = new Key(playerId, systemId, generation(world, systemId), columns, rows);
        RuntimeState runtime = runtime(world);
        VisibilityRules.Frame frame = VisibilityRules.frame(world, playerId);
        synchronized (ServerFogOfWarState.class) {
            Stored current = runtime.states.get(key);
            BitSet explored = current == null ? new BitSet(columns * rows) : current.explored();
            Map<String, FogOfWarView.KnownWormhole> wormholes = new LinkedHashMap<>();
            if (current != null) {
                for (FogOfWarView.KnownWormhole gate : current.wormholes()) wormholes.put(gate.id(), gate);
            }
            int exploredBefore = explored.cardinality();
            int wormholesBefore = wormholes.hashCode();
            for (VisibilityRules.Sensor sensor : frame.sensors()) reveal(explored, columns, rows, sensor);
            for (WormholeGate gate : world.wormholes) {
                if (gate == null || !frame.pointVisible(gate.x, gate.y)) continue;
                String id = gate.id == null || gate.id.isBlank()
                        ? gate.toSystemId + ':' + Math.round(gate.x) + ':' + Math.round(gate.y) : gate.id;
                if (id.isBlank() || gate.toSystemId == null || gate.toSystemId.isBlank()) continue;
                wormholes.put(id, new FogOfWarView.KnownWormhole(id, gate.toSystemId, gate.x, gate.y));
                if (wormholes.size() >= MAX_WORMHOLES) break;
            }
            if (current == null || explored.cardinality() != exploredBefore || wormholes.hashCode() != wormholesBefore) {
                long revision = current == null ? 1 : current.revision() + 1;
                runtime.states.put(key, new Stored(playerId, systemId, key.generation(), columns, rows,
                        revision, explored, List.copyOf(wormholes.values())));
                schedule(world, runtime);
            }
        }
    }

    private static void reveal(BitSet explored, int columns, int rows, VisibilityRules.Sensor sensor) {
        if (sensor == null || sensor.range() <= 0) return;
        int minColumn = clampCell((int)Math.floor((sensor.x() - sensor.range()) / CELL_SIZE), columns);
        int maxColumn = clampCell((int)Math.floor((sensor.x() + sensor.range()) / CELL_SIZE), columns);
        int minRow = clampCell((int)Math.floor((sensor.y() - sensor.range()) / CELL_SIZE), rows);
        int maxRow = clampCell((int)Math.floor((sensor.y() + sensor.range()) / CELL_SIZE), rows);
        for (int row = minRow; row <= maxRow; row++) {
            double top = row * (double)CELL_SIZE;
            double bottom = top + CELL_SIZE;
            double nearestY = Calc.clamp(sensor.y(), top, bottom);
            for (int column = minColumn; column <= maxColumn; column++) {
                double left = column * (double)CELL_SIZE;
                double right = left + CELL_SIZE;
                double nearestX = Calc.clamp(sensor.x(), left, right);
                double dx = nearestX - sensor.x();
                double dy = nearestY - sensor.y();
                if (dx * dx + dy * dy <= sensor.rangeSquared()) explored.set(row * columns + column);
            }
        }
    }

    private static synchronized RuntimeState runtime(World world) {
        return STATES.computeIfAbsent(world, ignored -> load(ServerFogOfWarStore.fromProcessArguments()));
    }

    private static RuntimeState load(ServerFogOfWarStore store) {
        RuntimeState runtime = new RuntimeState(store == null ? ServerFogOfWarStore.disabled() : store);
        int players = 0;
        Map<String,Integer> systemsByPlayer = new LinkedHashMap<>();
        for (Stored stored : runtime.store.load()) {
            if (stored == null || !realPlayerId(stored.playerId()) || invalidSystemId(stored.systemId())) continue;
            int systems = systemsByPlayer.getOrDefault(stored.playerId(), 0);
            if (!systemsByPlayer.containsKey(stored.playerId()) && players >= MAX_PLAYERS) continue;
            if (systems >= MAX_SYSTEMS_PER_PLAYER) continue;
            if (!systemsByPlayer.containsKey(stored.playerId())) players++;
            systemsByPlayer.put(stored.playerId(), systems + 1);
            runtime.states.put(stored.key(), stored.copy());
        }
        return runtime;
    }

    private static void schedule(World world, RuntimeState runtime) {
        if (!runtime.store.enabled()) return;
        if (runtime.pending != null) runtime.pending.cancel(false);
        runtime.pending = WRITER.schedule(() -> flush(world, runtime), WRITE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private static void flush(World world, RuntimeState runtime) {
        List<Stored> snapshot;
        synchronized (ServerFogOfWarState.class) {
            RuntimeState current = STATES.get(world);
            if (current != runtime) return;
            runtime.pending = null;
            snapshot = runtime.states.values().stream().map(Stored::copy).toList();
        }
        runtime.store.save(snapshot);
    }

    static String encode(Stored stored) {
        if (stored == null) return "";
        byte[] bits = stored.explored().toByteArray();
        StringBuilder raw = new StringBuilder();
        raw.append(FORMAT_VERSION).append('|')
                .append(encodeText(stored.playerId())).append('|')
                .append(encodeText(stored.systemId())).append('|')
                .append(stored.generation()).append('|')
                .append(stored.columns()).append('|')
                .append(stored.rows()).append('|')
                .append(stored.revision()).append('|')
                .append(Base64.getUrlEncoder().withoutPadding().encodeToString(bits)).append('|');
        List<FogOfWarView.KnownWormhole> gates = new ArrayList<>(stored.wormholes());
        gates.sort(Comparator.comparing(FogOfWarView.KnownWormhole::id));
        int count = 0;
        for (FogOfWarView.KnownWormhole gate : gates) {
            if (gate == null || count >= MAX_WORMHOLES) break;
            if (count > 0) raw.append(';');
            raw.append(encodeText(gate.id())).append(',')
                    .append(encodeText(gate.toSystemId())).append(',')
                    .append(Double.toHexString(gate.x())).append(',')
                    .append(Double.toHexString(gate.y()));
            count++;
        }
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.toString().getBytes(StandardCharsets.UTF_8));
        return encoded.length() <= MAX_PAYLOAD_LENGTH ? encoded : "";
    }

    static Stored decode(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_PAYLOAD_LENGTH) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 9);
            if (parts.length != 9 || Integer.parseInt(parts[0]) != FORMAT_VERSION) return null;
            String playerId = decodeText(parts[1]);
            String systemId = decodeText(parts[2]);
            long generation = Long.parseLong(parts[3]);
            int columns = Integer.parseInt(parts[4]);
            int rows = Integer.parseInt(parts[5]);
            long revision = Long.parseLong(parts[6]);
            if (!realPlayerId(playerId) || invalidSystemId(systemId) || columns <= 0 || rows <= 0
                    || (long)columns * rows > MAX_CELLS || revision < 0) return null;
            byte[] bytes = parts[7].isBlank() ? new byte[0] : Base64.getUrlDecoder().decode(parts[7]);
            int maxBytes = Math.max(1, (columns * rows + 7) / 8);
            if (bytes.length > maxBytes) return null;
            BitSet explored = BitSet.valueOf(bytes);
            explored.clear(columns * rows, Math.max(columns * rows, explored.length()));
            Map<String, FogOfWarView.KnownWormhole> wormholes = new LinkedHashMap<>();
            if (!parts[8].isBlank()) {
                for (String item : parts[8].split(";", -1)) {
                    if (wormholes.size() >= MAX_WORMHOLES) break;
                    String[] fields = item.split(",", -1);
                    if (fields.length != 4) continue;
                    String id = decodeText(fields[0]);
                    String target = decodeText(fields[1]);
                    double x = Double.valueOf(fields[2]);
                    double y = Double.valueOf(fields[3]);
                    if (id.isBlank() || target.isBlank() || !Double.isFinite(x) || !Double.isFinite(y)) continue;
                    wormholes.put(id, new FogOfWarView.KnownWormhole(id, target, x, y));
                }
            }
            return new Stored(playerId, systemId, generation, columns, rows, revision,
                    explored, List.copyOf(wormholes.values()));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static int columns(World world) {
        return Math.max(1, (int)Math.ceil(world.width / (double)CELL_SIZE));
    }

    private static int rows(World world) {
        return Math.max(1, (int)Math.ceil(world.height / (double)CELL_SIZE));
    }

    private static long generation(World world, String systemId) {
        return ClientEnvironmentSeed.forSystem(world.systemSeed(), systemId);
    }

    private static int clampCell(int value, int count) {
        return Math.max(0, Math.min(Math.max(0, count - 1), value));
    }

    private static boolean realPlayerId(String value) {
        return value != null && !value.isBlank() && !"WAIT".equals(value) && !NpcRules.isNpcFaction(value);
    }

    private static boolean invalidSystemId(String value) {
        return value == null || value.isBlank() || value.contains("WAIT");
    }

    private static String encodeText(String value) {
        String clean = clean(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(clean.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeText(String value) {
        if (value == null || value.isBlank() || value.length() > 1_024) return "";
        try { return clean(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)); }
        catch (IllegalArgumentException ex) { return ""; }
    }

    private static String clean(String value) {
        if (value == null) return "";
        String clean = value.replace("|", "").replace("\n", "").replace("\r", "").trim();
        return clean.length() <= 256 ? clean : clean.substring(0, 256);
    }

    record Key(String playerId, String systemId, long generation, int columns, int rows) { }

    record Stored(String playerId, String systemId, long generation, int columns, int rows, long revision,
                  BitSet explored, List<FogOfWarView.KnownWormhole> wormholes) {
        Stored {
            playerId = clean(playerId);
            systemId = clean(systemId);
            explored = explored == null ? new BitSet() : (BitSet)explored.clone();
            wormholes = wormholes == null ? List.of() : List.copyOf(wormholes);
        }

        Key key() { return new Key(playerId, systemId, generation, columns, rows); }
        Stored copy() { return new Stored(playerId, systemId, generation, columns, rows, revision, explored, wormholes); }
    }

    private static final class RuntimeState {
        final ServerFogOfWarStore store;
        final Map<Key, Stored> states = new LinkedHashMap<>();
        ScheduledFuture<?> pending;

        RuntimeState(ServerFogOfWarStore store) {
            this.store = Objects.requireNonNull(store);
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "starchem-server-fog-persistence");
            thread.setDaemon(true);
            return thread;
        }
    }

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Map<World, RuntimeState> snapshot;
            synchronized (ServerFogOfWarState.class) {
                snapshot = new LinkedHashMap<>(STATES);
            }
            for (Map.Entry<World, RuntimeState> entry : snapshot.entrySet()) flush(entry.getKey(), entry.getValue());
        }, "starchem-server-fog-shutdown"));
    }
}
