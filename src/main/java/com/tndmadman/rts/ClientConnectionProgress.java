package com.tndmadman.rts;

enum ConnectionPhase {
    CONNECTING,
    HANDSHAKING,
    SYNCHRONIZING,
    READY,
    RECONNECTING,
    FAILED,
    DISCONNECTED
}

record ClientConnectionProgress(ConnectionPhase phase, String title, String detail,
                                int stage, int stageCount, long elapsedMillis) {
    ClientConnectionProgress {
        phase = phase == null ? ConnectionPhase.CONNECTING : phase;
        title = title == null ? "" : title;
        detail = detail == null ? "" : detail;
        stageCount = Math.max(1, stageCount);
        stage = Math.max(0, Math.min(stage, stageCount));
        elapsedMillis = Math.max(0, elapsedMillis);
    }

    boolean ready() { return phase == ConnectionPhase.READY; }
    boolean failed() { return phase == ConnectionPhase.FAILED; }
}
