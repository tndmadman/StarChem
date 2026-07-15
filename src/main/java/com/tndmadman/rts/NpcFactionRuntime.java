package com.tndmadman.rts;

final class NpcFactionRuntime {
    enum State { INITIALIZING, ACTIVE, RESPAWNING }

    private final String factionId;
    private final String homeSystemId;
    private State state = State.INITIALIZING;
    private double spawnTimer;
    private int spawnCount;

    NpcFactionRuntime(NpcFaction faction) {
        this.factionId = faction.id();
        this.homeSystemId = homeSystemId(faction);
        this.spawnTimer = Math.max(0, faction.firstSpawnSeconds());
    }

    String factionId() { return factionId; }
    String homeSystemId() { return homeSystemId; }
    State state() { return state; }
    double spawnTimer() { return Math.max(0, spawnTimer); }
    int spawnCount() { return spawnCount; }

    void observe(boolean hasGalaxyAssets, NpcFaction faction) {
        if (hasGalaxyAssets) {
            state = State.ACTIVE;
            return;
        }
        if (state == State.ACTIVE) {
            state = State.RESPAWNING;
            spawnTimer = Math.max(0, faction.respawnSeconds());
        }
    }

    boolean advanceSpawn(String activeSystemId, boolean requirementsMet, double dt) {
        if (state == State.ACTIVE || !homeSystemId.equals(activeSystemId)) return false;
        if (!requirementsMet || !Double.isFinite(dt) || dt <= 0) return false;
        spawnTimer -= dt;
        return spawnTimer <= 0;
    }

    void markSpawned(NpcFaction faction) {
        state = State.ACTIVE;
        spawnTimer = Math.max(0, faction.respawnSeconds());
        spawnCount++;
    }

    void deferSpawn(double seconds) {
        spawnTimer = Math.max(0.25, seconds);
    }

    private static String homeSystemId(NpcFaction faction) {
        if (Config.CORSAIRS_ID.equals(faction.id())) return StarSystems.CORSAIR_SYSTEM_ID;
        return StarSystems.DEFAULT_SYSTEM_ID;
    }
}
