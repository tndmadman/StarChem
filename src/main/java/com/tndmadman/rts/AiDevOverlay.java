package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.Line2D;

final class AiDevOverlay {
    void drawWorld(Graphics2D g2, World world) {
        if (!AiDevSettings.overlay) return;
        for (NpcFaction f : NpcRules.factions()) {
            if (!f.enabled()) continue;
            Base first = firstBase(world, f.id());
            if (first != null) AiDevSnapshot.drawLabel(g2, world, f, first.x + 120, first.y - 150);
        }
        if (AiDevSettings.pathLines) drawLines(g2, world);
    }

    private void drawLines(Graphics2D g2, World world) {
        Stroke old = g2.getStroke();
        g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{14, 10}, 0));
        for (Unit u : world.units.values()) {
            if (!NpcRules.isNpcFaction(u.playerId)) continue;
            Color c = PlayerRegistry.color(u.playerId);
            g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 140));
            if (!u.attackTarget.isBlank() && CombatTarget.alive(world, u.attackTarget)) {
                g2.draw(new Line2D.Double(u.x, u.y, CombatTarget.x(world, u.attackTarget), CombatTarget.y(world, u.attackTarget)));
                continue;
            }
            ResourceNode node = world.findResource(u.automationResourceId);
            if (node != null) {
                g2.draw(new Line2D.Double(u.x, u.y, node.x, node.y));
                continue;
            }
            if (u.task == UnitTask.MOVE || u.task == UnitTask.RETURN_TO_STATION) g2.draw(new Line2D.Double(u.x, u.y, u.targetX, u.targetY));
        }
        g2.setStroke(old);
    }

    private Base firstBase(World world, String playerId) {
        for (Base b : world.bases.values()) if (b.playerId.equals(playerId) && b.hp > 0) return b;
        return null;
    }
}
