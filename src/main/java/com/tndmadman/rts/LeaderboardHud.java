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
            drawObjective(g2, world, screenW, y + h + 8);
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
        drawObjective(g2, world, screenW, y + h + 8);
    }

    private void drawObjective(Graphics2D g2, World world, int screenW, int y) {
        ObjectiveView objective = ObjectiveSystem.view(world);
        if (!objective.enabled()) return;
        int w = 320;
        int h = 62;
        int x = Math.max(12, screenW - w - 14);
        g2.setColor(new Color(0, 0, 0, 185));
        g2.fillRoundRect(x, y, w, h, 12, 12);
        g2.setColor(objective.completed()
                ? new Color(105, 220, 145, 190)
                : new Color(245, 190, 75, 180));
        g2.drawRoundRect(x, y, w, h, 12, 12);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
        g2.setColor(Color.WHITE);
        g2.drawString("MATCH OBJECTIVE", x + 12, y + 19);
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
        g2.setColor(new Color(220, 238, 250));
        g2.drawString(objective.description(), x + 12, y + 39);
        String progress = objective.progressLabel();
        if (objective.completed() && !objective.completedBy().isBlank()) {
            progress += " - " + objective.completedBy();
        } else if (!objective.leader().isBlank() && objective.current() > 0) {
            progress += " - Leader: " + objective.leader();
        }
        g2.setColor(objective.completed() ? new Color(150, 245, 180) : new Color(255, 220, 135));
        g2.drawString(progress, x + 12, y + 55);
    }

    private List<Row> rows(World world) {
        List<LeaderboardEntry> global = GlobalLeaderboard.get(world);
        if (!global.isEmpty()) return globalRows(global);
        return activeSystemRows(world);
    }

    private List<Row> globalRows(List<LeaderboardEntry> entries) {
        List<Row> out = new ArrayList<>();
        for (LeaderboardEntry entry : entries) {
            if (entry.units() + entry.bases() <= 0) continue;
            out.add(new Row(entry.playerId(), PlayerRegistry.name(entry.playerId()), entry.units(), entry.bases(), entry.score()));
        }
        out.sort(Comparator.comparingInt(Row::score).reversed());
        return out;
    }

    private List<Row> activeSystemRows(World world) {
        List<Row> out = new ArrayList<>();
        for (PlayerInfo player : PlayerRegistry.snapshotPlayers()) {
            int units = 0;
            int bases = 0;
            double hp = 0;
            for (Unit unit : world.units.values()) if (unit.playerId.equals(player.id()) && unit.hp > 0) { units++; hp += unit.hp; }
            for (Base base : world.bases.values()) if (base.playerId.equals(player.id()) && base.hp > 0) { bases++; hp += base.hp; }
            if (units + bases <= 0) continue;
            out.add(new Row(player.id(), PlayerRegistry.name(player.id()), units, bases,
                    (int)Math.round(hp + bases * 1000.0 + units * 100.0)));
        }
        out.sort(Comparator.comparingInt(Row::score).reversed());
        return out;
    }

    private record Row(String playerId, String name, int units, int bases, int score) { }
}
