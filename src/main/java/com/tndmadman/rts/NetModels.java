package com.tndmadman.rts;

import java.net.InetAddress;
import java.util.List;

record PlayerInfo(String id, String name, int rgb, boolean local) { }
record UnitState(String playerId, int unitId, String shipTypeId, double x, double y, double targetX, double targetY, double heading, String task, int resourceId, String packageType, String cargo, double hp, double shield, String attackTarget, double weaponFlashTimer) {
    UnitState(String playerId, int unitId, String shipTypeId, double x, double y, double targetX, double targetY, double heading, String task, int resourceId, String packageType, String cargo, double hp, String attackTarget, double weaponFlashTimer) {
        this(playerId, unitId, shipTypeId, x, y, targetX, targetY, heading, task, resourceId, packageType, cargo, hp, Rules.ship(shipTypeId).maxShield, attackTarget, weaponFlashTimer);
    }
}
record ResourceState(int id, String name, String kind, String material, double x, double y, double maxAmount, double harvestRate, double radius, double amount, boolean active, double respawnTimer, double orbitCenterX, double orbitCenterY, double orbitRadius, double orbitAngle, double orbitSpeed, boolean orbiting) {
    ResourceState(int id, String name, String kind, String material, double x, double y, double maxAmount, double harvestRate, double radius, double amount, boolean active, double respawnTimer) {
        this(id, name, kind, material, x, y, maxAmount, harvestRate, radius, amount, active, respawnTimer, x, y, 0, 0, 0, false);
    }
}
record BaseState(String id, String playerId, String typeId, double x, double y, double hp, double shield, String cargo) {
    BaseState(String id, String playerId, String typeId, double x, double y, double hp, String cargo) {
        this(id, playerId, typeId, x, y, hp, Rules.base(typeId).maxShield, cargo);
    }
}
record StockState(String playerId, String cargo) { }
record ShotState(int id, String ownerId, String weaponId, String targetKey, double x, double y, double lastX, double lastY) { }
record ItemState(int id, String material, double amount, double x, double y, double vx, double vy, double angle, double spin) { }
record Snapshot(long sequence, List<PlayerInfo> players, List<UnitState> units, List<ResourceState> resources, List<BaseState> bases, List<StockState> stocks, List<ShotState> shots, List<ItemState> items, String systemId, double systemTime, String celestialState) {
    Snapshot(long sequence, List<PlayerInfo> players, List<UnitState> units, List<ResourceState> resources, List<BaseState> bases, List<StockState> stocks, List<ShotState> shots, List<ItemState> items, String systemId, double systemTime) {
        this(sequence, players, units, resources, bases, stocks, shots, items, systemId, systemTime, "");
    }
    Snapshot(long sequence, List<PlayerInfo> players, List<UnitState> units, List<ResourceState> resources, List<BaseState> bases, List<StockState> stocks, List<ShotState> shots, List<ItemState> items, double systemTime) {
        this(sequence, players, units, resources, bases, stocks, shots, items, "", systemTime, "");
    }
    Snapshot(long sequence, List<PlayerInfo> players, List<UnitState> units, List<ResourceState> resources, List<BaseState> bases, List<StockState> stocks, List<ShotState> shots, List<ItemState> items) {
        this(sequence, players, units, resources, bases, stocks, shots, items, "", -1, "");
    }
    Snapshot(long sequence, List<PlayerInfo> players, List<UnitState> units, List<ResourceState> resources, List<BaseState> bases, List<StockState> stocks, List<ShotState> shots) {
        this(sequence, players, units, resources, bases, stocks, shots, List.of(), "", -1, "");
    }
    Snapshot(long sequence, List<PlayerInfo> players, List<UnitState> units, List<ResourceState> resources, List<BaseState> bases, List<StockState> stocks) {
        this(sequence, players, units, resources, bases, stocks, List.of(), List.of(), "", -1, "");
    }
}
record NetPacket(String message, InetAddress address, int port) { }
record ServerPeer(String playerId, InetAddress address, int port, long lastSeen, boolean devFreeBuild) { }
record PendingReliable(String id, String payload, InetAddress address, int port, long lastSent, int attempts) { }

interface CommandSink {
    void move(MoveCommand command);
    void work(HarvestCommand command);
    void attack(AttackCommand command);
    void respawn(String playerId);
    void build(String playerId, String baseId, String shipTypeId);
    void basePackage(String playerId, String mode, String baseOrUnitId, String packageType);
}