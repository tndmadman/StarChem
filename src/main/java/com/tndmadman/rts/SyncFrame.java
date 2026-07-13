package com.tndmadman.rts;

final class SyncFrame {
    private SyncFrame() { }

    static String write(Snapshot snapshot) { return write(snapshot, 0); }

    static String write(Snapshot snapshot, long viewRevision) {
        return token() + Math.max(0, viewRevision) + '|' + SnapshotWriter.write(snapshot);
    }

    static Snapshot read(String message) {
        return SnapshotReader.read(payload(message));
    }

    static long viewRevision(String message) {
        if (!matches(message)) return 0;
        String body = message.substring(token().length());
        int split = body.indexOf('|');
        if (split <= 0) return 0;
        try { return Math.max(0, Long.parseLong(body.substring(0, split))); }
        catch (NumberFormatException ignored) { return 0; }
    }

    static boolean matches(String message) {
        return message != null && message.startsWith(token());
    }

    private static String payload(String message) {
        if (!matches(message)) return message;
        String body = message.substring(token().length());
        int split = body.indexOf('|');
        if (split <= 0) return body;
        try {
            Long.parseLong(body.substring(0, split));
            return body.substring(split + 1);
        } catch (NumberFormatException ignored) {
            return body;
        }
    }

    private static String token() { return "VIEW_SYNC|"; }
}
