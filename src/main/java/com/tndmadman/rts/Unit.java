package com.tndmadman.rts;

import java.util.EnumMap;

final class Unit {
    final String playerId;
    final int unitId;
    final EnumMap<Material, Double> inventory = new EnumMap<>(Material.class);
    String shipTypeId;
    String basePackageType = "";
    String attackTarget = "";
    String logisticsTargetBaseId = "";
    String logisticsRequestId = "";
    String orderTarget = "";
    UnitTask task = UnitTask.IDLE;
    UnitOrderType orderType = UnitOrderType.NONE;
    double x, y, targetX, targetY, heading = -Math.PI / 2, orbitAngle, orbitRetarget;
    double weaponCooldown, weaponFlashTimer, wormholeCooldown;
    double hp, shield, shieldDelayTimer;
    double miningAnchorX, miningAnchorY;
    double orderX1, orderY1, orderX2, orderY2, orderRadius;
    int automationResourceId = -1;
    int orderPhase;
    boolean selected, unloadingThisFrame, miningAnchorSet;

    Unit(String playerId, int unitId, String shipTypeId, double x, double y) {
        this.playerId = playerId;
        this.unitId = unitId;
        this.shipTypeId = shipTypeId;
        this.x = x;
        this.y = y;
        this.targetX = x;
        this.targetY = y;
        this.miningAnchorX = x;
        this.miningAnchorY = y;
        this.hp = type().maxHp;
        this.shield = type().maxShield;
        this.orbitAngle = unitId;
    }

    String key() { return key(playerId, unitId); }
    static String key(String playerId, int unitId) { return playerId + ":" + unitId; }
    ShipType type() { return Rules.ship(shipTypeId); }
    boolean contains(double wx, double wy) { return Calc.distance(wx, wy, x, y) <= 28 * type().size.scale; }

    void issueMove(double tx, double ty) {
        clearOrder();
        if (canAutoMineLocally()) setMiningAnchor(tx, ty);
        moveTo(tx, ty);
    }

    void moveTo(double tx, double ty) {
        targetX = tx;
        targetY = ty;
        task = UnitTask.MOVE;
        automationResourceId = -1;
        attackTarget = "";
    }

    void issueAttack(String targetKey) {
        clearOrder();
        attack(targetKey);
    }

    void attack(String targetKey) {
        attackTarget = targetKey == null ? "" : targetKey;
        task = attackTarget.isBlank() ? UnitTask.IDLE : UnitTask.ATTACK;
        automationResourceId = -1;
    }

    void startAutoHarvest(int resourceId) {
        clearOrder();
        automationResourceId = resourceId;
        attackTarget = "";
        task = UnitTask.AUTO_HARVEST;
    }

    void setOrder(UnitOrderCommand command) {
        if (command == null || command.type() == UnitOrderType.NONE) {
            clearOrder();
            return;
        }
        orderType = command.type();
        orderX1 = command.x1();
        orderY1 = command.y1();
        orderX2 = command.x2();
        orderY2 = command.y2();
        orderRadius = command.radius();
        orderTarget = command.targetKey();
        orderPhase = command.phase();
        automationResourceId = -1;
        attackTarget = "";
        logisticsTargetBaseId = "";
        logisticsRequestId = "";
        task = UnitTask.IDLE;
    }

    UnitOrderCommand orderCommand() {
        return new UnitOrderCommand(playerId, unitId, orderType, orderX1, orderY1, orderX2, orderY2, orderRadius, orderTarget, orderPhase);
    }

    void clearOrder() {
        orderType = UnitOrderType.NONE;
        orderX1 = orderY1 = orderX2 = orderY2 = orderRadius = 0;
        orderTarget = "";
        orderPhase = 0;
    }

    void setMiningAnchor(double x, double y) {
        miningAnchorX = x;
        miningAnchorY = y;
        miningAnchorSet = true;
    }

    private boolean canAutoMineLocally() {
        ShipType type = type();
        return type.scoutRange > 0 && !type.harvestKinds.isEmpty();
    }

    double cargoUsed() {
        double total = 0;
        for (double value : inventory.values()) total += value;
        return total;
    }

    double freeCargo() { return Math.max(0, type().cargoCapacity - cargoUsed()); }

    void addCargo(Material material, double amount) {
        inventory.put(material, inventory.getOrDefault(material, 0.0) + amount);
    }

    void updatePosition(double dt, int mapW, int mapH) {
        double dx = targetX - x;
        double dy = targetY - y;
        double dist = Math.hypot(dx, dy);
        if (dist > 2) {
            heading = Math.atan2(dy, dx);
            double step = Math.min(dist, type().speed * dt);
            x += dx / dist * step;
            y += dy / dist * step;
        }
        x = Calc.clamp(x, 0, mapW);
        y = Calc.clamp(y, 0, mapH);
    }
}
