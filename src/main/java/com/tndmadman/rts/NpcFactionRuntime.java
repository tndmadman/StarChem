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
        this.homeSystemId = homeSystemIdFor(faction);
        this.spawnTimer = Math.max(0, faction.firstSpawnSeconds());
    }

    String factionId() { return factionId; }
    String homeSystemId() { return homeSystemId; }
    State state() { return state; }
    double spawnTimer() { return Math.max(0, spawnTimer); }
    int spawnCount() { return spawnCount; }

    void restore(State state, double spawnTimer, int spawnCount) {
        this.state = state == null ? State.INITIALIZING : state;
        this.spawnTimer = Math.max(0, spawnTimer);
        this.spawnCount = Math.max(0, spawnCount);
    }

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

    static String homeSystemIdFor(NpcFaction faction) {
        if (faction != null && Config.CORSAIRS_ID.equals(faction.id())) return StarSystems.CORSAIR_SYSTEM_ID;
        return StarSystems.DEFAULT_SYSTEM_ID;
    }
}
