package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.Line2D;

final class UnitOrderSystem {
    private static final double ARRIVAL_DISTANCE = 8.0;

    private UnitOrderSystem() { }

    static double defaultRadius(UnitOrderType type) {
        if (type == null) return 0;
        return switch (type) {
            case PATROL -> 420;
            case GUARD -> 500;
            case ESCORT -> 520;
            case ATTACK_MOVE -> 480;
            case HOLD, NONE -> 0;
        };
    }

    static void update(World world, Unit unit, double dt) {
        if (world == null || unit == null || unit.orderType == UnitOrderType.NONE) return;
        if (unit.task == UnitTask.AUTO_HARVEST || unit.task == UnitTask.RETURN_TO_STATION) return;

        if (unit.task == UnitTask.ATTACK) {
            if (unit.attackTarget.isBlank()
                    || !CombatTarget.enemy(world, unit, unit.attackTarget)
                    || !canEngage(world, unit, CombatTarget.x(world, unit.attackTarget), CombatTarget.y(world, unit.attackTarget))) {
                clearTemporaryAttack(unit);
            } else {
                return;
            }
        }

        switch (unit.orderType) {
            case PATROL -> updatePatrol(world, unit);
            case GUARD -> updateGuard(world, unit);
            case ESCORT -> updateEscort(world, unit);
            case HOLD -> moveOrStop(world, unit, unit.orderX1, unit.orderY1, 4);
            case ATTACK_MOVE -> updateAttackMove(world, unit);
            case NONE -> { }
        }
    }

    static boolean canAcquire(Unit unit) {
        if (unit == null || !unit.attackTarget.isBlank()) return false;
        if (unit.orderType == UnitOrderType.NONE) return unit.task == UnitTask.IDLE;
        return unit.task == UnitTask.IDLE || unit.task == UnitTask.MOVE;
    }

    static double acquisitionRange(World world, Unit unit) {
        double weaponRange = AttackRangeRules.effectiveWeaponRange(world, unit);
        if (unit.orderType == UnitOrderType.NONE || unit.orderType == UnitOrderType.HOLD) return weaponRange;
        return Math.max(weaponRange * 1.25, Math.min(700, Math.max(220, unit.orderRadius)));
    }

    static double acquisitionRange(Unit unit) {
        return acquisitionRange(PlayerRegistry.activeWorld(), unit);
    }

    static boolean mayChase(Unit unit) {
        return unit != null && unit.orderType != UnitOrderType.HOLD;
    }

    static boolean canEngage(World world, Unit unit, double targetX, double targetY) {
        if (world == null || unit == null) return false;
        double radius = Math.max(40, unit.orderRadius);
        return switch (unit.orderType) {
            case NONE -> true;
            case HOLD -> Calc.distance(unit.orderX1, unit.orderY1, targetX, targetY)
                    <= Math.max(1, AttackRangeRules.effectiveWeaponRange(world, unit));
            case GUARD -> Calc.distance(anchorX(world, unit), anchorY(world, unit), targetX, targetY) <= radius;
            case ESCORT -> Calc.distance(anchorX(world, unit), anchorY(world, unit), targetX, targetY) <= radius;
            case PATROL, ATTACK_MOVE -> distanceToSegment(targetX, targetY, unit.orderX1, unit.orderY1, unit.orderX2, unit.orderY2) <= radius;
        };
    }

    static double anchorX(World world, Unit unit) {
        Unit targetUnit = CombatTarget.unit(world, unit.orderTarget);
        if (targetUnit != null && targetUnit.hp > 0
                && DiplomacySystem.allied(world, unit.playerId, targetUnit.playerId)) return targetUnit.x;
        Base targetBase = CombatTarget.base(world, unit.orderTarget);
        if (targetBase != null && targetBase.hp > 0
                && DiplomacySystem.allied(world, unit.playerId, targetBase.playerId)) return targetBase.x;
        return unit.orderX1;
    }

    static double anchorY(World world, Unit unit) {
        Unit targetUnit = CombatTarget.unit(world, unit.orderTarget);
        if (targetUnit != null && targetUnit.hp > 0
                && DiplomacySystem.allied(world, unit.playerId, targetUnit.playerId)) return targetUnit.y;
        Base targetBase = CombatTarget.base(world, unit.orderTarget);
        if (targetBase != null && targetBase.hp > 0
                && DiplomacySystem.allied(world, unit.playerId, targetBase.playerId)) return targetBase.y;
        return unit.orderY1;
    }

    private static void updatePatrol(World world, Unit unit) {
        double tx = unit.orderPhase == 0 ? unit.orderX1 : unit.orderX2;
        double ty = unit.orderPhase == 0 ? unit.orderY1 : unit.orderY2;
        if (Calc.distance(unit.x, unit.y, tx, ty) <= ARRIVAL_DISTANCE) {
            unit.orderPhase = unit.orderPhase == 0 ? 1 : 0;
            tx = unit.orderPhase == 0 ? unit.orderX1 : unit.orderX2;
            ty = unit.orderPhase == 0 ? unit.orderY1 : unit.orderY2;
        }
        moveOrStop(world, unit, tx, ty, 4);
    }

    private static void updateGuard(World world, Unit unit) {
        if (!unit.orderTarget.isBlank() && !friendlyTarget(world, unit, unit.orderTarget, false)) unit.orderTarget = "";
        double cx = anchorX(world, unit);
        double cy = anchorY(world, unit);
        double returnDistance = Math.max(70, unit.orderRadius * 0.35);
        if (Calc.distance(unit.x, unit.y, cx, cy) > returnDistance) moveOrStop(world, unit, cx, cy, 6);
        else stop(unit);
    }

    private static void updateEscort(World world, Unit unit) {
        Unit escorted = CombatTarget.unit(world, unit.orderTarget);
        if (escorted == null || escorted.hp <= 0
                || !DiplomacySystem.allied(world, unit.playerId, escorted.playerId) || escorted == unit) {
            holdHere(unit);
            return;
        }
        double angle = Math.floorMod(unit.unitId, 12) * (Math.PI * 2.0 / 12.0);
        double followDistance = 105 + unit.type().size.scale * 18;
        double tx = escorted.x + Math.cos(angle) * followDistance;
        double ty = escorted.y + Math.sin(angle) * followDistance;
        moveOrStop(world, unit, tx, ty, 28);
    }

    private static void updateAttackMove(World world, Unit unit) {
        if (Calc.distance(unit.x, unit.y, unit.orderX2, unit.orderY2) <= ARRIVAL_DISTANCE) {
            unit.clearOrder();
            stop(unit);
            return;
        }
        moveOrStop(world, unit, unit.orderX2, unit.orderY2, 4);
    }

    private static void holdHere(Unit unit) {
        unit.orderType = UnitOrderType.HOLD;
        unit.orderX1 = unit.x;
        unit.orderY1 = unit.y;
        unit.orderX2 = unit.x;
        unit.orderY2 = unit.y;
        unit.orderRadius = 0;
        unit.orderTarget = "";
        unit.orderPhase = 0;
        clearTemporaryAttack(unit);
    }

    private static void moveOrStop(World world, Unit unit, double x, double y, double tolerance) {
        double tx = Calc.clamp(x, 0, world.width);
        double ty = Calc.clamp(y, 0, world.height);
        unit.targetX = tx;
        unit.targetY = ty;
        unit.attackTarget = "";
        unit.task = Calc.distance(unit.x, unit.y, tx, ty) <= tolerance ? UnitTask.IDLE : UnitTask.MOVE;
    }

    private static void stop(Unit unit) {
        unit.targetX = unit.x;
        unit.targetY = unit.y;
        unit.attackTarget = "";
        unit.task = UnitTask.IDLE;
    }

    private static void clearTemporaryAttack(Unit unit) {
        unit.attackTarget = "";
        if (unit.task == UnitTask.ATTACK) unit.task = UnitTask.IDLE;
    }

    private static boolean friendlyTarget(World world, Unit unit, String key, boolean unitOnly) {
        Unit targetUnit = CombatTarget.unit(world, key);
        if (targetUnit != null) return targetUnit != unit && targetUnit.hp > 0
                && DiplomacySystem.allied(world, unit.playerId, targetUnit.playerId);
        if (unitOnly) return false;
        Base targetBase = CombatTarget.base(world, key);
        return targetBase != null && targetBase.hp > 0
                && DiplomacySystem.allied(world, unit.playerId, targetBase.playerId);
    }

    private static double distanceToSegment(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        if (Math.abs(dx) + Math.abs(dy) < 0.0001) return Calc.distance(px, py, ax, ay);
        double t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy);
        t = Calc.clamp(t, 0, 1);
        return Calc.distance(px, py, ax + dx * t, ay + dy * t);
    }
}

final class AUnitOrder {
    private AUnitOrder() { }

    static boolean apply(World world, UnitOrderCommand command) {
        if (world == null || command == null || command.type() == null || command.type() == UnitOrderType.NONE) return false;
        Unit unit = world.units.get(Unit.key(command.playerId(), command.unitId()));
        if (unit == null || !unit.playerId.equals(command.playerId())
                || ProductionSystem.refitReserved(world, unit.key())) return false;
        if (!finite(command.x1(), command.y1(), command.x2(), command.y2(), command.radius())) return false;

        String target = command.targetKey() == null ? "" : command.targetKey().trim();
        if (command.type() == UnitOrderType.ESCORT && !friendlyTarget(world, unit, target, true)) return false;
        if (command.type() == UnitOrderType.GUARD && !target.isBlank() && !friendlyTarget(world, unit, target, false)) return false;
        if (command.type() == UnitOrderType.PATROL && Calc.distance(command.x1(), command.y1(), command.x2(), command.y2()) < 20) return false;
        if (command.type() != UnitOrderType.ESCORT && command.type() != UnitOrderType.GUARD) target = "";

        double radius = command.radius() > 0 ? command.radius() : UnitOrderSystem.defaultRadius(command.type());
        radius = Calc.clamp(radius, 0, 1200);
        UnitOrderCommand safe = new UnitOrderCommand(
                command.playerId(), command.unitId(), command.type(),
                Calc.clamp(command.x1(), 0, world.width), Calc.clamp(command.y1(), 0, world.height),
                Calc.clamp(command.x2(), 0, world.width), Calc.clamp(command.y2(), 0, world.height),
                radius, target, Math.max(0, command.phase()));
        unit.setOrder(safe);
        UnitOrderSystem.update(world, unit, 0);
        return true;
    }

    private static boolean friendlyTarget(World world, Unit unit, String key, boolean unitOnly) {
        Unit targetUnit = CombatTarget.unit(world, key);
        if (targetUnit != null) return targetUnit != unit && targetUnit.hp > 0
                && DiplomacySystem.allied(world, unit.playerId, targetUnit.playerId);
        if (unitOnly) return false;
        Base targetBase = CombatTarget.base(world, key);
        return targetBase != null && targetBase.hp > 0
                && DiplomacySystem.allied(world, unit.playerId, targetBase.playerId);
    }

    private static boolean finite(double... values) {
        for (double value : values) if (Double.isNaN(value) || Double.isInfinite(value)) return false;
        return true;
    }
}

final class UnitOrderRenderer {
    private UnitOrderRenderer() { }

    static void draw(Graphics2D g2, World world, Unit unit) {
        if (g2 == null || world == null || unit == null || !unit.selected || !PlayerRegistry.isLocal(unit.playerId) || unit.orderType == UnitOrderType.NONE) return;
        Color color = PlayerRegistry.color(unit.playerId);
        Graphics2D r = (Graphics2D) g2.create();
        r.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 185));
        r.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{10f, 8f}, 0));
        switch (unit.orderType) {
            case PATROL -> {
                r.draw(new Line2D.Double(unit.orderX1, unit.orderY1, unit.orderX2, unit.orderY2));
                marker(r, unit.orderX1, unit.orderY1, 8);
                marker(r, unit.orderX2, unit.orderY2, 8);
                double tx = unit.orderPhase == 0 ? unit.orderX1 : unit.orderX2;
                double ty = unit.orderPhase == 0 ? unit.orderY1 : unit.orderY2;
                r.draw(new Line2D.Double(unit.x, unit.y, tx, ty));
            }
            case GUARD -> {
                double x = UnitOrderSystem.anchorX(world, unit);
                double y = UnitOrderSystem.anchorY(world, unit);
                circle(r, x, y, unit.orderRadius);
                r.draw(new Line2D.Double(unit.x, unit.y, x, y));
            }
            case ESCORT -> {
                double x = UnitOrderSystem.anchorX(world, unit);
                double y = UnitOrderSystem.anchorY(world, unit);
                r.draw(new Line2D.Double(unit.x, unit.y, x, y));
                marker(r, x, y, 10);
            }
            case HOLD -> marker(r, unit.orderX1, unit.orderY1, 13);
            case ATTACK_MOVE -> {
                r.draw(new Line2D.Double(unit.x, unit.y, unit.orderX2, unit.orderY2));
                marker(r, unit.orderX2, unit.orderY2, 12);
            }
            case NONE -> { }
        }
        r.dispose();
    }

    private static void circle(Graphics2D g2, double x, double y, double radius) {
        int d = (int)Math.round(radius * 2);
        g2.drawOval((int)Math.round(x - radius), (int)Math.round(y - radius), d, d);
    }

    private static void marker(Graphics2D g2, double x, double y, int radius) {
        int cx = (int)Math.round(x);
        int cy = (int)Math.round(y);
        g2.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);
        g2.drawLine(cx - radius - 4, cy, cx + radius + 4, cy);
        g2.drawLine(cx, cy - radius - 4, cx, cy + radius + 4);
    }
}
