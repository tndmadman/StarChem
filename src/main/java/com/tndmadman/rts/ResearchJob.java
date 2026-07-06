package com.tndmadman.rts;

final class ResearchJob {
    final String playerId;
    final String baseId;
    final String topicId;
    final double duration;
    double remaining;

    ResearchJob(String playerId, String baseId, String topicId, double duration) {
        this.playerId = playerId;
        this.baseId = baseId;
        this.topicId = topicId;
        this.duration = Math.max(1.0, duration);
        this.remaining = this.duration;
    }

    double progress() {
        return 1.0 - Math.max(0.0, remaining) / duration;
    }
}
