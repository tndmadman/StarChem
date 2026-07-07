package com.tndmadman.rts;

final class ResourceSyncMode {
    private static final ThreadLocal<Boolean> FULL = ThreadLocal.withInitial(() -> false);

    private ResourceSyncMode() { }

    static void fullForNextSnapshot() {
        FULL.set(true);
    }

    static boolean consumeFull() {
        boolean full = FULL.get();
        FULL.set(false);
        return full;
    }
}
