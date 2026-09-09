package com.tndmadman.rts;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

final class Base {
    final String id;
    final String playerId;
    final String typeId;
    final double x, y;
    final EnumMap<Material, Double> inventory = new EnumMap<>(Material.class);
    final List<ProductionJob> productionQueue = new ArrayList<>();
    long nextProductionJobId = 1;
    String logisticsStatus = "";
    double hp, shield, shieldDelayTimer;

    Base(String id, String playerId, String typeId, double x, double y) {
        this.id = id; this.playerId = playerId; this.typeId = typeId; this.x = x; this.y = y;
        this.hp = type().maxHp;
        this.shield = type().maxShield;
    }

    BaseType type() { return Rules.base(typeId); }

    boolean canRefit(Unit unit) {
        if (unit == null || !type().canRefitShips || hp <= 0) return false;
        return playerId.equals(unit.playerId) && unit.hp > 0
                && Calc.distance(x, y, unit.x, unit.y) <= type().refitRange;
    }

    boolean contains(double wx, double wy) {
        return Calc.distance(wx, wy, x, y) <= interactionRadius();
    }

    /**
     * Gameplay/hit-test radius retained from the pre-#396 station renderer.
     * Authored station geometry and presentation may extend beyond this value.
     */
    double interactionRadius() {
        return switch (typeId) {
            case "signal_jammer" -> 68;
            case "radar_decoy" -> 72;
            case IntelWarfareSystem.CONTACT_STATION -> 48;
            case "shipyard" -> 82;
            case "laboratory", "manufacturing" -> 74;
            case RadarTowerRules.TIER_ONE -> 54;
            case RadarTowerRules.TIER_TWO -> 66;
            case RadarTowerRules.TIER_THREE -> 78;
            default -> 64;
        };
    }

    void draw(Graphics2D g2, Color ignoredColor, EnumMap<Material, Double> ignoredStockpile, boolean ignoredLocal) {
        Color playerColor = PlayerRegistry.color(playerId);
        Graphics2D s = (Graphics2D)g2.create();
        s.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        BaseType def = type();
        double visualRadius = StationRenderer.visualExtent(this);

        StationRenderer.draw(s, this, playerColor);
        DamageStateEffects.drawBase(s, this, visualRadius);
        StationPresentation.draw(s, this, def, visualRadius, playerColor);
        s.dispose();
    }
}
