package com.tndmadman.rts;

final class SystemControlState {
    private String controllerId;
    private String claimantId = "";
    private SystemControlStatus status;
    private double captureProgress;
    private double changedAt;

    SystemControlState(SystemLifetime lifetime, String initialControllerId) {
        controllerId = clean(initialControllerId);
        if (lifetime == SystemLifetime.PLAYER_HOME) {
            status = SystemControlStatus.PROTECTED;
            captureProgress = 1;
        } else if (controllerId.isBlank()) {
            status = SystemControlStatus.NEUTRAL;
        } else {
            status = SystemControlStatus.CONTROLLED;
            captureProgress = 1;
        }
    }

    String controllerId() { return controllerId; }
    String claimantId() { return claimantId; }
    SystemControlStatus status() { return status; }
    double captureProgress() { return captureProgress; }
    double changedAt() { return changedAt; }

    void restore(String controllerId, String claimantId, SystemControlStatus status, double captureProgress, double changedAt) {
        this.controllerId = clean(controllerId);
        this.claimantId = clean(claimantId);
        this.status = status == null ? (this.controllerId.isBlank() ? SystemControlStatus.NEUTRAL : SystemControlStatus.CONTROLLED) : status;
        this.captureProgress = clamp(captureProgress);
        this.changedAt = Math.max(0, changedAt);
    }

    void protect(String ownerId) {
        controllerId = clean(ownerId);
        claimantId = "";
        status = SystemControlStatus.PROTECTED;
        captureProgress = 1;
    }

    void neutral(double time) {
        controllerId = "";
        claimantId = "";
        status = SystemControlStatus.NEUTRAL;
        captureProgress = 0;
        changedAt = Math.max(0, time);
    }

    void controlled(String ownerId, double time) {
        controllerId = clean(ownerId);
        claimantId = "";
        status = controllerId.isBlank() ? SystemControlStatus.NEUTRAL : SystemControlStatus.CONTROLLED;
        captureProgress = controllerId.isBlank() ? 0 : 1;
        changedAt = Math.max(0, time);
    }

    void contested() {
        claimantId = "";
        status = SystemControlStatus.CONTESTED;
    }

    void capture(String ownerId, double delta) {
        String cleanOwner = clean(ownerId);
        if (cleanOwner.isBlank()) return;
        if (!cleanOwner.equals(claimantId)) {
            claimantId = cleanOwner;
            captureProgress = controllerId.isBlank() ? 0 : Math.max(0, 1 - captureProgress);
        }
        status = SystemControlStatus.CAPTURING;
        captureProgress = clamp(captureProgress + Math.max(0, delta));
    }

    boolean captureComplete() { return status == SystemControlStatus.CAPTURING && captureProgress >= 0.999999; }

    void decay(double delta) {
        if (status != SystemControlStatus.CAPTURING) return;
        captureProgress = clamp(captureProgress - Math.max(0, delta));
        if (captureProgress <= 0.000001) {
            claimantId = "";
            status = controllerId.isBlank() ? SystemControlStatus.NEUTRAL : SystemControlStatus.CONTROLLED;
            captureProgress = controllerId.isBlank() ? 0 : 1;
        }
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
}
