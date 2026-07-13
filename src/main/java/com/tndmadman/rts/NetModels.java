package com.tndmadman.rts;

import java.net.InetAddress;
import java.util.List;

record PlayerInfo(String id, String name, int rgb, boolean local) { }
record UnitState(String playerId, int unitId, String shipTypeId, double x, double y, double targetX, double targetY,
                 double heading, String task, int resourceId, String packageType, String cargo, double hp, double shield,
                 String attackTarget, double weaponFlashTimer, String orderType, double orderX1, double orderY1,
                 double orderX2, double orderY2, double orderRadius, String orderTarget, int orderPhase) {
    UnitState(String playerId, int unitId, String shipTypeId, double x, double y, double targetX, double targetY,
              double heading, String task, int resourceId, String packageType, String cargo, double hp, double shield,
              String attackTarget, double weaponFlashTimer) {
        this(playerId, unitId, shipTypeId, x, y, targetX, targetY, heading, task, resourceId, packageType, cargo,
                hp, shield, attackTarget, weaponFlashTimer, UnitOrderType.NONE.name(), 0, 0, 0, 0, 0, "", 0);
    }

    UnitState(String playerId, int unitId, String shipTypeId, double x, double y, double targetX, double targetY,
              double heading, String task, int resourceId, String packageType, String cargo, double hp,
              String attackTarget, double weaponFlashTimer) {
        this(playerId, unitId, shipTypeId, x, y, targetX, targetY, heading, task, resourceId, packageType, cargo,
                hp, Rules.ship(shipTypeId).maxShield, attackTarget, weaponFlashTimer);
    }

    UnitState(String playerId, int unitId, String shipTypeId, double x, double y, double targetX, double targetY,
              double heading, String task, int resourceId, String packageType, String cargo) {
        this(playerId, unitId, shipTypeId, x, y, targetX, targetY, heading, task, resourceId, packageType, cargo,
                Rules.ship(shipTypeId).maxHp, Rules.ship(shipTypeId).maxShield, "", 0);
    }
}
record ResourceState(int id, String name, String kind, String material, double x, double y, double maxAmount, double harvestRate, double radius, double amount, boolean active, double respawnTimer, double orbitCenterX, double orbitCenterY, double orbitRadius, double orbitAngle, double orbitSpeed, boolean orbiting) {
    ResourceState(int id, String name, String kind, String material, double x, double y, double maxAmount, double harvestRate, double radius, double amount, boolean active, double respawnTimer) {
        this(id, name, kind, material, x, y, maxAmount, harvestRate, radius, amount, active, respawnTimer, x, y, 0, 0, 0, false);
    }
}
record BaseState(String id, String playerId, String typeId, double x, double y, double hp, double shield, String cargo, String productionQueue) {
    BaseState(String id, String playerId, String typeId, double x, double y, double hp, double shield, String cargo) {
        this(id, playerId, typeId, x, y, hp, shield, cargo, "");
    }

    BaseState(String id, String playerId, String typeId, double x, double y, double hp, String cargo) {
        this(id, playerId, typeId, x, y, hp, Rules.base(typeId).maxShield, cargo, "");
    }

    BaseState(String id, String playerId, String typeId, double x, double y) {
        this(id, playerId, typeId, x, y, Rules.base(typeId).maxHp, Rules.base(typeId).maxShield, "", "");
    }
}
record StockState(String playerId, String cargo) { }
record ShotState(int id, String ownerId, String weaponId, String targetKey, double x, double y, double lastX, double lastY) { }
record ItemState(int id, String material, double amount, double x, double y, double vx, double vy, double angle, double spin) { }
record ResearchState(String playerId, List<String> topicIds) {
    ResearchState {
        playerId = playerId == null ? "" : playerId;
        topicIds = topicIds == null ? List.of() : List.copyOf(topicIds);
    }
}
record Snapshot(long sequence, List<PlayerInfo> players, List<UnitState> units, List<ResourceState> resources, List<BaseState> bases, List<StockState> stocks, List<ShotState> shots, List<ItemState> items, String systemId, double systemTime, String celestialState, List<ResearchState> research) {
    Snapshot {
        String packedSystem = systemId == null ? "" : systemId;
        String embeddedState = CelestialPacketCache.state(packedSystem);
        systemId = CelestialPacketCache.systemId(packedSystem);
        if (celestialState == null || celestialState.isBlank()) celestialState = embeddedState;
        research = research == null ? List.of() : List.copyOf(research);
    }

    Snapshot(long sequence, List<PlayerInfo> players, List<UnitState> units, List<ResourceState> resources,
             List<BaseState> bases, List<StockState> stocks, List<ShotState> shots, List<ItemState> items,
             String systemId, double systemTime, String celestialState) {
        this(sequence, players, units, resources, bases, stocks, shots, items, systemId, systemTime, celestialState, List.of());
    }

    Snapshot(long sequence, List<PlayerInfo> players, List<UnitState> units, List<ResourceState> resources,
             List<BaseState> bases, List<StockState> stocks, List<ShotState> shots, List<ItemState> items,
             String systemId, double systemTime, List<ResearchState> research) {
        this(sequence, players, units, resources, bases, stocks, shots, items, systemId, systemTime, "", research);
    }

    Snapshot(long sequence, List<PlayerInfo> players, List<UnitState> units, List<ResourceState> resources, List<BaseState> bases, List<StockState> stocks, List<ShotState> shots, List<ItemState> items, String systemId, double systemTime) {
        this(sequence, players, units, resources, bases, stocks, shots, items, systemId, systemTime, "", List.of());
    }
    Snapshot(long sequence, List<PlayerInfo> players, List<UnitState> units, List<ResourceState> resources, List<BaseState> bases, List<StockState> stocks, List<ShotState> shots, List<ItemState> items, double systemTime) {
        this(sequence, players, units, resources, bases, stocks, shots, items, "", systemTime, "", List.of());
    }
    Snapshot(long sequence, List<PlayerInfo> players, List<UnitState> units, List<ResourceState> resources, List<BaseState> bases, List<StockState> stocks, List<ShotState> shots, List<ItemState> items) {
        this(sequence, players, units, resources, bases, stocks, shots, items, "", -1, "", List.of());
    }
    Snapshot(long sequence, List<PlayerInfo> players, List<UnitState> units, List<ResourceState> resources, List<BaseState> bases, List<StockState> stocks, List<ShotState> shots) {
        this(sequence, players, units, resources, bases, stocks, shots, List.of(), "", -1, "", List.of());
    }
    Snapshot(long sequence, List<PlayerInfo> players, List<UnitState> units, List<ResourceState> resources, List<BaseState> bases, List<StockState> stocks) {
        this(sequence, players, units, resources, bases, stocks, List.of(), List.of(), "", -1, "", List.of());
    }

    String packedSystemField() {
        return CelestialPacketCache.pack(systemId, celestialState);
    }

    void stageCelestialState() {
        CelestialPacketCache.receive(systemId, celestialState);
    }
}
record ConnectionId(long value) {
    static final ConnectionId NONE = new ConnectionId(0);
    boolean valid() { return value > 0; }
    @Override public String toString() { return valid() ? Long.toUnsignedString(value) : "none"; }
}

enum DeliveryClass {
    ORDERED,
    REGULAR_SNAPSHOT,
    FULL_CORRECTION,
    VIEW_SNAPSHOT,
    LEADERBOARD,
    GALAXY
}

record NetPacket(String message, ConnectionId connectionId, InetAddress address, int port) {
    NetPacket(String message, InetAddress address, int port) { this(message, ConnectionId.NONE, address, port); }
}
record ServerPeer(String playerId, ConnectionId connectionId, InetAddress address, int port,
                  long lastSeen, boolean devFreeBuild) { }
record ConnectionDiagnostics(ConnectionId connectionId, boolean open, int queuedFrames, long queuedBytes,
                             long coalescedSnapshots) { }

interface CommandSink {
    void move(MoveCommand command);
    void work(HarvestCommand command);
    void attack(AttackCommand command);
    void order(UnitOrderCommand command);
    void respawn(String playerId);
    void build(String playerId, String baseId, String shipTypeId);
    void basePackage(String playerId, String mode, String baseOrUnitId, String packageType);
    void production(String playerId, String action, String baseId, String value, String extra);
}
