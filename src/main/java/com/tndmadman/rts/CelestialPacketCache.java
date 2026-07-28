package com.tndmadman.rts;

final class CelestialPacketCache {
    private static final String SEP = "~";
    private static final String WORMHOLE_SEP = "~W~";
    private static final ThreadLocal<String> OUT = new ThreadLocal<>();
    private static final ThreadLocal<String> IN_STATE = new ThreadLocal<>();
    private static final ThreadLocal<String> IN_SYSTEM = new ThreadLocal<>();
    private static final ThreadLocal<Long> IN_SEED = new ThreadLocal<>();
    private static final ThreadLocal<String> IN_WORMHOLES = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> IN_HAS_WORMHOLES = new ThreadLocal<>();

    private CelestialPacketCache() { }

    static void capture(World world) {
        if (world == null) return;
        OUT.set(ClientEnvironmentSeed.forActiveSystem(world) + SEP + CelestialSnapshotSync.write(world)
                + WORMHOLE_SEP + WormholeSnapshotSync.write(world));
    }

    static String pack(String systemId) {
        String state = OUT.get();
        OUT.remove();
        return pack(systemId, state);
    }

    static String pack(String systemId, String state) {
        String id = systemId == null ? "" : systemId;
        String data = state == null ? "" : state;
        return data.isBlank() ? id : id + SEP + data;
    }

    static String systemId(String packed) {
        if (packed == null) return "";
        int cut = packed.indexOf(SEP);
        return cut < 0 ? packed : packed.substring(0, cut);
    }

    static String state(String packed) {
        return data(packed);
    }

    static long seed(long fallback) {
        Long value = IN_SEED.get();
        return value == null ? fallback : value;
    }

    static String receivedSystemId() {
        String value = IN_SYSTEM.get();
        return value == null ? "" : value;
    }

    static void validateState(String state) {
        Decoded decoded = decode(state);
        if (decoded.hasWormholes()) WormholeSnapshotSync.validate(decoded.wormholes());
    }

    static void receive(String systemId, String state) {
        receive(pack(systemId, state));
    }

    static void receive(String packed) {
        clear();
        String id = systemId(packed);
        IN_SYSTEM.set(id);
        String data = data(packed);
        if (data.isBlank()) return;
        Decoded decoded = decode(data);
        if (decoded.seed() != null) IN_SEED.set(decoded.seed());
        if (!decoded.celestial().isBlank()) IN_STATE.set(decoded.celestial());
        if (decoded.hasWormholes()) {
            IN_HAS_WORMHOLES.set(true);
            IN_WORMHOLES.set(decoded.wormholes());
        }
    }

    static boolean apply(World world) {
        boolean applied = false;
        String celestial = IN_STATE.get();
        if (celestial != null && !celestial.isBlank()) {
            CelestialSnapshotSync.apply(world, celestial);
            applied = true;
        }
        if (Boolean.TRUE.equals(IN_HAS_WORMHOLES.get())) {
            WormholeSnapshotSync.apply(world, IN_WORMHOLES.get());
            applied = true;
        }
        IN_STATE.remove();
        IN_WORMHOLES.remove();
        IN_HAS_WORMHOLES.remove();
        return applied;
    }

    static void clear() {
        IN_STATE.remove();
        IN_SYSTEM.remove();
        IN_SEED.remove();
        IN_WORMHOLES.remove();
        IN_HAS_WORMHOLES.remove();
    }

    private static Decoded decode(String data) {
        if (data == null || data.isBlank()) return new Decoded(null, "", "", false);
        int seedCut = data.indexOf(SEP);
        Long seed = null;
        String body = data;
        if (seedCut >= 0) {
            try { seed = Long.parseLong(data.substring(0, seedCut)); }
            catch (NumberFormatException ignored) { seed = null; }
            body = data.substring(seedCut + 1);
        }
        int wormholeCut = body.indexOf(WORMHOLE_SEP);
        if (wormholeCut < 0) return new Decoded(seed, body, "", false);
        return new Decoded(seed, body.substring(0, wormholeCut),
                body.substring(wormholeCut + WORMHOLE_SEP.length()), true);
    }

    private static String data(String packed) {
        if (packed == null) return "";
        int cut = packed.indexOf(SEP);
        return cut < 0 || cut + 1 >= packed.length() ? "" : packed.substring(cut + 1);
    }

    private record Decoded(Long seed, String celestial, String wormholes, boolean hasWormholes) { }
}
