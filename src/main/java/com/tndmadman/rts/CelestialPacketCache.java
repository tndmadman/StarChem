package com.tndmadman.rts;

final class CelestialPacketCache {
    private static final String SEP = "~";
    private static final ThreadLocal<String> OUT = new ThreadLocal<>();
    private static final ThreadLocal<String> IN_STATE = new ThreadLocal<>();
    private static final ThreadLocal<String> IN_SYSTEM = new ThreadLocal<>();
    private static final ThreadLocal<Long> IN_SEED = new ThreadLocal<>();

    private CelestialPacketCache() { }

    static void capture(World world) {
        if (world == null) return;
        OUT.set(world.systemSeed() + SEP + CelestialSnapshotSync.write(world));
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

    static void receive(String systemId, String state) {
        receive(pack(systemId, state));
    }

    static void receive(String packed) {
        String id = systemId(packed);
        IN_SYSTEM.set(id);
        String data = data(packed);
        if (data.isBlank()) {
            IN_STATE.remove();
            IN_SEED.remove();
            return;
        }
        int cut = data.indexOf(SEP);
        if (cut < 0) {
            IN_STATE.set(data);
            IN_SEED.remove();
            return;
        }
        try { IN_SEED.set(Long.parseLong(data.substring(0, cut))); }
        catch (NumberFormatException ignored) { IN_SEED.remove(); }
        IN_STATE.set(data.substring(cut + 1));
    }

    static boolean apply(World world) {
        String data = IN_STATE.get();
        IN_STATE.remove();
        if (data == null || data.isBlank()) return false;
        CelestialSnapshotSync.apply(world, data);
        return true;
    }

    static void clear() {
        IN_STATE.remove();
        IN_SYSTEM.remove();
        IN_SEED.remove();
    }

    private static String data(String packed) {
        if (packed == null) return "";
        int cut = packed.indexOf(SEP);
        return cut < 0 || cut + 1 >= packed.length() ? "" : packed.substring(cut + 1);
    }
}
