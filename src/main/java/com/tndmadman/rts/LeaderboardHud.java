package com.tndmadman.rts;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class LeaderboardHud {
    void draw(Graphics2D g2, World world, int screenW) {
        List<Row> rows = rows(world);
        int w = 260;
        int rowH = 20;
        int h = 34 + Math.max(1, rows.size()) * rowH;
        int x = Math.max(12, screenW - w - 14);
        int y = 12;
        g2.setColor(new Color(0, 0, 0, 175));
        g2.fillRoundRect(x, y, w, h, 12, 12);
        g2.setColor(new Color(90, 190, 245, 150));
        g2.drawRoundRect(x, y, w, h, 12, 12);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
        g2.setColor(Color.WHITE);
        g2.drawString("LEADERBOARD", x + 12, y + 20);
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
        if (rows.isEmpty()) {
            g2.setColor(new Color(220, 225, 185));
            g2.drawString("No active commanders", x + 16, y + 44);
            return;
        }
        int line = y + 42;
        int rank = 1;
        for (Row row : rows) {
            Color color = PlayerRegistry.color(row.playerId);
            g2.setColor(color);
            g2.drawString(rank + ". " + row.name, x + 12, line);
            g2.setColor(new Color(220, 238, 250));
            String stat = row.units + " ships  " + row.bases + " bases  " + row.score;
            g2.drawString(stat, x + w - 12 - g2.getFontMetrics().stringWidth(stat), line);
            line += rowH;
            rank++;
        }
    }

    private List<Row> rows(World world) {
        List<Row> out = new ArrayList<>();
        for (PlayerInfo player : PlayerRegistry.snapshotPlayers()) {
            int units = 0;
            int bases = 0;
            double hp = 0;
            for (Unit unit : world.units.values()) if (unit.playerId.equals(player.id()) && unit.hp > 0) { units++; hp += unit.hp; }
            for (Base base : world.bases.values()) if (base.playerId.equals(player.id()) && base.hp > 0) { bases++; hp += base.hp; }
            if (units + bases <= 0) continue;
            out.add(new Row(player.id(), player.name(), units, bases, (int)Math.round(hp + bases * 1000.0 + units * 100.0)));
        }
        out.sort(Comparator.comparingInt(Row::score).reversed());
        return out;
    }

    private record Row(String playerId, String name, int units, int bases, int score) { }
}
