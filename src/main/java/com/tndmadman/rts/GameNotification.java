package com.tndmadman.rts;

final class GameNotification {
    final String text;
    final double lifetime;
    double age;

    GameNotification(String text, double lifetime) {
        this.text = text == null ? "" : text;
        this.lifetime = Math.max(0.5, lifetime);
    }

    boolean expired() {
        return age >= lifetime;
    }

    float alpha() {
        double fadeStart = lifetime * 0.45;
        if (age <= fadeStart) return 1.0f;
        return (float)Math.max(0.0, 1.0 - (age - fadeStart) / Math.max(0.1, lifetime - fadeStart));
    }
}
