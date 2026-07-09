package com.tndmadman.rts;

final class SyncFrame {
    private SyncFrame() { }

    static String write(Snapshot snapshot) {
        return token() + SnapshotWriter.write(snapshot);
    }

    static Snapshot read(String message) {
        String prefix = token();
        return message.startsWith(prefix) ? SnapshotReader.read(message.substring(prefix.length())) : SnapshotReader.read(message);
    }

    static boolean matches(String message) {
        return message.startsWith(token());
    }

    private static String token() {
        return new String(new char[]{86,73,69,87,95,83,89,78,67,124});
    }
}
