package com.tndmadman.rts;

final class CelestialPacketCache {
    private static final String SEP = "~";
    private static final ThreadLocal<String> OUT = new ThreadLocal<>();
    private static final ThreadLocal<String> IN = new ThreadLocal<>();

    private CelestialPacketCache() { }

    static void capture(World world) {
        OUT.set(CelestialSnapshotSync.write(world));
    }

    static String pack(String systemId) {
        String state = OUT.get();
        OUT.remove();
        String id = systemId == null ? "" : systemId;
        return state == null || state.isBlank() ? id : id + SEP + state;
    }

    static String systemId(String packed) {
        if (packed == null) return "";
        int cut = packed.indexOf(SEP);
        return cut < 0 ? packed : packed.substring(0, cut);
    }

    static void receive(String packed) {
        String data = data(packed);
        if (data.isBlank()) IN.remove();
        else IN.set(data);
    }

    static boolean apply(World world) {
        String data = IN.get();
        IN.remove();
        if (data == null || data.isBlank()) return false;
        CelestialSnapshotSync.apply(world, data);
        return true;
    }

    private static String data(String packed) {
        if (packed == null) return "";
        int cut = packed.indexOf(SEP);
        return cut < 0 || cut + 1 >= packed.length() ? "" : packed.substring(cut + 1);
    }
}
