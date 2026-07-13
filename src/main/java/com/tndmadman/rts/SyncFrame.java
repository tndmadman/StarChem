package com.tndmadman.rts;

final class SyncFrame {
    private static final String VIEW_TOKEN = "VIEW_SYNC|";
    private static final String RESOURCE_TOKEN = "RESOURCE_SYNC|";

    private SyncFrame() { }

    static String write(Snapshot snapshot) { return writeView(snapshot, 0); }
    static String write(Snapshot snapshot, long viewRevision) { return writeView(snapshot, viewRevision); }

    static String writeView(Snapshot snapshot, long viewRevision) {
        return VIEW_TOKEN + Math.max(0, viewRevision) + '|' + SnapshotWriter.write(snapshot);
    }

    static String writeResourceCorrection(Snapshot snapshot) {
        return RESOURCE_TOKEN + SnapshotWriter.write(snapshot);
    }

    static Snapshot read(String message) {
        return SnapshotReader.read(payload(message));
    }

    static long viewRevision(String message) {
        if (!isView(message)) return 0;
        String body = message.substring(VIEW_TOKEN.length());
        int split = body.indexOf('|');
        if (split <= 0) return 0;
        try { return Math.max(0, Long.parseLong(body.substring(0, split))); }
        catch (NumberFormatException ignored) { return 0; }
    }

    static boolean isView(String message) {
        return message != null && message.startsWith(VIEW_TOKEN);
    }

    static boolean isResourceCorrection(String message) {
        return message != null && message.startsWith(RESOURCE_TOKEN);
    }

    static boolean matches(String message) {
        return isView(message) || isResourceCorrection(message);
    }

    private static String payload(String message) {
        if (isResourceCorrection(message)) return message.substring(RESOURCE_TOKEN.length());
        if (!isView(message)) return message;
        String body = message.substring(VIEW_TOKEN.length());
        int split = body.indexOf('|');
        if (split <= 0) return body;
        try {
            Long.parseLong(body.substring(0, split));
            return body.substring(split + 1);
        } catch (NumberFormatException ignored) {
            return body;
        }
    }
}
