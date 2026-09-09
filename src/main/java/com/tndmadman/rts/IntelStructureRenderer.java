package com.tndmadman.rts;

import java.awt.Color;
import java.awt.Graphics2D;

/** Status overlays for intel structures. Physical station geometry is owned by {@link StationRenderer}. */
final class IntelStructureRenderer {
    private IntelStructureRenderer() { }

    static void drawStatus(Graphics2D source, Base base, double radius) {
        if (source == null || base == null) return;
        DamageStateEffects.drawBase(source, base, radius);
        if (!PlayerRegistry.isLocal(base.playerId)) return;
        String text = "";
        Color color = new Color(125, 225, 255);
        if (IntelWarfareSystem.isRadar(base.typeId)) {
            IntelWarfareSystem.RadarMode mode = IntelWarfareSystem.radarMode(PlayerRegistry.activeWorld(), base);
            text = "RADAR " + mode.name() + " | ADAPTIVE";
        } else if (IntelWarfareSystem.isJammer(base.typeId)) {
            text = "ECM ACTIVE";
            color = new Color(215, 120, 255);
        } else if (IntelWarfareSystem.isDecoy(base.typeId)) {
            text = "FALSE SIGNATURE ACTIVE";
            color = new Color(255, 185, 105);
        }
        if (text.isBlank()) return;
        int width = source.getFontMetrics().stringWidth(text) + 12;
        int x = (int)Math.round(base.x - width / 2.0);
        int y = (int)Math.round(base.y + radius + 34);
        source.setColor(new Color(0, 0, 0, 170));
        source.fillRoundRect(x, y, width, 18, 8, 8);
        source.setColor(color);
        source.drawString(text, x + 6, y + 13);
    }
}
