package com.tndmadman.rts;

import java.util.EnumMap;

final class Unit {
    final String playerId;
    final int unitId;
    final EnumMap<Material, Double> inventory = new EnumMap<>(Material.class);
    String shipTypeId;
    String loadoutId;
    String basePackageType = "";
    String attackTarget = "";
    String logisticsTargetBaseId = "";
    String logisticsRequestId = "";
    String orderTarget = "";
    UnitTask task = UnitTask.IDLE;
    UnitOrderType orderType = UnitOrderType.NONE;
    double x, y, targetX, targetY, heading = -Math.PI / 2, orbitAngle, orbitRetarget;
    double weaponCooldown, weaponFlashTimer, wormholeCooldown;
    double microJumpCooldown, microJumpFlashTimer;
    double hp, shield, shieldDelayTimer;
    double miningAnchorX, miningAnchorY;
    double orderX1, orderY1, orderX2, orderY2, orderRadius;
    int automationResourceId = -1;
    int orderPhase;
    boolean selected, unloadingThisFrame, miningAnchorSet, afterburnerActive;

    Unit(String playerId, int unitId, String shipTypeId, double x, double y) {
        this.playerId = playerId;
        this.unitId = unitId;
        this.shipTypeId = shipTypeId;
        this.loadoutId = WeaponRules.defaultLoadoutId(shipTypeId);
        this.x = Double.isFinite(x) ? x : 0;
        this.y = Double.isFinite(y) ? y : 0;
        this.targetX = this.x;
        this.targetY = this.y;
        this.miningAnchorX = this.x;
        this.miningAnchorY = this.y;
        this.hp = type().maxHp;
        this.shield = type().maxShield;
        this.orbitAngle = unitId;
    }

    String key() { return key(playerId, unitId); }
    static String key(String playerId, int unitId) { return playerId + ":" + unitId; }
    ShipType type() { return Rules.ship(shipTypeId); }
    boolean contains(double wx, double wy) { return Calc.distance(wx, wy, x, y) <= 28 * type().size.scale; }

    void issueMove(double tx, double ty) {
        if (refitLocked() || !GameplayCommandNumbers.finite(tx, ty)) return;
        clearOrder();
        if (canAutoMineLocally()) setMiningAnchor(tx, ty);
        moveTo(tx, ty);
    }

    void moveTo(double tx, double ty) {
        if (!GameplayCommandNumbers.finite(tx, ty)) return;
        targetX = tx;
        targetY = ty;
        task = UnitTask.MOVE;
        automationResourceId = -1;
        attackTarget = "";
    }

    void issueAttack(String targetKey) {
        if (refitLocked()) return;
        clearOrder();
        attack(targetKey);
    }

    void attack(String targetKey) {
        String candidate = targetKey == null ? "" : targetKey.trim();
        World world = PlayerRegistry.activeWorld();
        if (world != null && !candidate.isBlank()
                && !CombatTarget.mayTarget(world, playerId, candidate)) candidate = "";
        attackTarget = candidate;
        task = attackTarget.isBlank() ? UnitTask.IDLE : UnitTask.ATTACK;
        automationResourceId = -1;
    }

    void startAutoHarvest(int resourceId) {
        if (refitLocked()) return;
        clearOrder();
        automationResourceId = resourceId;
        attackTarget = "";
        task = UnitTask.AUTO_HARVEST;
    }

    void setOrder(UnitOrderCommand command) {
        if (refitLocked()) return;
        if (command == null || command.type() == UnitOrderType.NONE) {
            clearOrder();
            return;
        }
        if (!GameplayCommandNumbers.finite(command.x1(), command.y1(), command.x2(), command.y2(), command.radius())
                || !GameplayCommandNumbers.orderRadius(command.radius())
                || command.phase() < 0 || command.phase() > 1) return;
        World world = PlayerRegistry.activeWorld();
        if (world != null && (command.type() == UnitOrderType.ESCORT || command.type() == UnitOrderType.GUARD)
                && command.targetKey() != null && !command.targetKey().isBlank()) {
            String targetOwner = CombatTarget.owner(world, command.targetKey());
            if (!DiplomacySystem.allied(world, playerId, targetOwner)) return;
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
        if (!GameplayCommandNumbers.finite(x, y)) return;
        miningAnchorX = x;
        miningAnchorY = y;
        miningAnchorSet = true;
    }

    private boolean canAutoMineLocally() {
        ShipType type = type();
        return type.scoutRange > 0 && !type.harvestKinds.isEmpty();
    }

    private boolean refitLocked() {
        World world = PlayerRegistry.activeWorld();
        if (world == null) return false;
        if (!ProductionSystem.refitReserved(world, key())) return false;
        if (PlayerRegistry.isLocal(playerId)) world.status = "Ship is reserved for refitting.";
        return true;
    }

    double cargoUsed() {
        double total = 0;
        for (double value : inventory.values()) if (Double.isFinite(value) && value > 0) total += value;
        return total;
    }

    double freeCargo() { return Math.max(0, type().cargoCapacity - cargoUsed()); }

    void addCargo(Material material, double amount) {
        if (material == null || !Double.isFinite(amount)) return;
        double updated = inventory.getOrDefault(material, 0.0) + amount;
        if (Double.isFinite(updated)) inventory.put(material, updated);
    }

    void updatePosition(double dt, int mapW, int mapH) {
        double width = Math.max(0, mapW);
        double height = Math.max(0, mapH);
        boolean corruptedPosition = !GameplayCommandNumbers.finite(x, y);
        boolean corruptedTarget = !GameplayCommandNumbers.finite(targetX, targetY);

        x = GameplayCommandNumbers.repairedCoordinate(x, targetX, width);
        y = GameplayCommandNumbers.repairedCoordinate(y, targetY, height);
        targetX = GameplayCommandNumbers.repairedCoordinate(targetX, x, width);
        targetY = GameplayCommandNumbers.repairedCoordinate(targetY, y, height);

        if (!Double.isFinite(heading)) heading = -Math.PI / 2;
        if (!GameplayCommandNumbers.finite(miningAnchorX, miningAnchorY)) {
            miningAnchorX = x;
            miningAnchorY = y;
            miningAnchorSet = false;
        }
        if (!GameplayCommandNumbers.finite(orderX1, orderY1, orderX2, orderY2, orderRadius)
                || !GameplayCommandNumbers.orderRadius(orderRadius)
                || orderPhase < 0 || orderPhase > 1) clearOrder();
        if (corruptedPosition || corruptedTarget) {
            targetX = x;
            targetY = y;
            task = UnitTask.IDLE;
            automationResourceId = -1;
            attackTarget = "";
            clearOrder();
        }
        if (!Double.isFinite(dt) || dt <= 0) return;

        double dx = targetX - x;
        double dy = targetY - y;
        double dist = Math.hypot(dx, dy);
        if (!Double.isFinite(dist)) {
            targetX = x;
            targetY = y;
            task = UnitTask.IDLE;
            clearOrder();
            return;
        }
        if (dist > 2) {
            double desiredHeading = Math.atan2(dy, dx);
            if (afterburnerActive) {
                double maxTurn = 1.35 * ShipModuleRules.agilityMultiplier(this) * dt;
                heading += Calc.clamp(angleDelta(heading, desiredHeading), -maxTurn, maxTurn);
            } else {
                heading = desiredHeading;
            }
            double step = Math.min(dist, type().speed * ShipModuleRules.speedMultiplier(this) * dt);
            x += Math.cos(heading) * step;
            y += Math.sin(heading) * step;
        }
        x = GameplayCommandNumbers.repairedCoordinate(x, targetX, width);
        y = GameplayCommandNumbers.repairedCoordinate(y, targetY, height);
    }

    private static double angleDelta(double from, double to) {
        double delta = to - from;
        while (delta > Math.PI) delta -= Math.PI * 2;
        while (delta < -Math.PI) delta += Math.PI * 2;
        return delta;
    }
}
