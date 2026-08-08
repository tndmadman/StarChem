package com.tndmadman.rts;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

final class CombatPolicyHud {
    interface ChangeHandler {
        int apply(CombatStance stance, TargetPriorityPolicy priority);
    }

    private static final int X = 12;
    private static final int Y = 150;
    private static final int BUTTON_W = 205;
    private static final int BUTTON_H = 28;
    private static final int GAP = 6;

    void draw(Graphics2D g2, World world) {
        if (g2 == null || world == null) return;
        List<Unit> selected = selected(world);
        if (selected.isEmpty()) return;

        Rectangle stance = stanceBounds();
        Rectangle priority = priorityBounds();
        Rectangle panel = new Rectangle(X, Y, BUTTON_W * 2 + GAP + 16, BUTTON_H + 16);
        g2.setColor(new Color(0, 0, 0, 188));
        g2.fillRoundRect(panel.x, panel.y, panel.width, panel.height, 12, 12);
        g2.setColor(new Color(70, 135, 175));
        g2.drawRoundRect(panel.x, panel.y, panel.width, panel.height, 12, 12);

        drawButton(g2, stance, "STANCE: " + stanceLabel(world, selected));
        drawButton(g2, priority, "TARGET: " + priorityLabel(world, selected));
    }

    boolean click(MouseEvent event, World world, ChangeHandler handler) {
        if (event == null || world == null || handler == null || !javax.swing.SwingUtilities.isLeftMouseButton(event)) {
            return false;
        }
        List<Unit> selected = selected(world);
        if (selected.isEmpty()) return false;
        if (stanceBounds().contains(event.getPoint())) {
            CombatStance current = commonStance(world, selected);
            CombatStance next = current == null ? CombatStance.AGGRESSIVE : next(current, event.isShiftDown());
            int applied = handler.apply(next, null);
            world.status = applied > 0
                    ? "Combat stance: " + next.label + " for " + applied + " ship(s)."
                    : "Combat stance change was rejected.";
            ProceduralAudio.play(applied > 0 ? SoundCue.SELECT : SoundCue.ERROR);
            return true;
        }
        if (priorityBounds().contains(event.getPoint())) {
            TargetPriorityPolicy current = commonPriority(world, selected);
            TargetPriorityPolicy next = current == null
                    ? TargetPriorityPolicy.NEAREST_THREAT : next(current, event.isShiftDown());
            int applied = handler.apply(null, next);
            world.status = applied > 0
                    ? "Target priority: " + next.label + " for " + applied + " ship(s)."
                    : "Target priority change was rejected.";
            ProceduralAudio.play(applied > 0 ? SoundCue.SELECT : SoundCue.ERROR);
            return true;
        }
        return false;
    }

    private void drawButton(Graphics2D g2, Rectangle bounds, String label) {
        g2.setColor(new Color(18, 70, 104, 235));
        g2.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 9, 9);
        g2.setColor(new Color(110, 215, 255));
        g2.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 9, 9);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 11f));
        g2.setColor(Color.WHITE);
        int tx = bounds.x + (bounds.width - g2.getFontMetrics().stringWidth(label)) / 2;
        g2.drawString(label, Math.max(bounds.x + 6, tx), bounds.y + 19);
    }

    private String stanceLabel(World world, List<Unit> selected) {
        CombatStance stance = commonStance(world, selected);
        return stance == null ? "MIXED" : stance.label.toUpperCase(java.util.Locale.ROOT);
    }

    private String priorityLabel(World world, List<Unit> selected) {
        TargetPriorityPolicy priority = commonPriority(world, selected);
        return priority == null ? "MIXED" : priority.label.toUpperCase(java.util.Locale.ROOT);
    }

    private CombatStance commonStance(World world, List<Unit> selected) {
        CombatStance common = null;
        for (Unit unit : selected) {
            CombatStance value = CombatPolicySystem.stance(world, unit);
            if (common == null) common = value;
            else if (common != value) return null;
        }
        return common;
    }

    private TargetPriorityPolicy commonPriority(World world, List<Unit> selected) {
        TargetPriorityPolicy common = null;
        for (Unit unit : selected) {
            TargetPriorityPolicy value = CombatPolicySystem.priority(world, unit);
            if (common == null) common = value;
            else if (common != value) return null;
        }
        return common;
    }

    private List<Unit> selected(World world) {
        List<Unit> out = new ArrayList<>();
        for (Unit unit : world.selectedUnits()) {
            if (unit != null && unit.hp > 0 && PlayerRegistry.isLocal(unit.playerId)) out.add(unit);
        }
        return out;
    }

    private CombatStance next(CombatStance current, boolean reverse) {
        CombatStance[] values = CombatStance.values();
        int delta = reverse ? -1 : 1;
        return values[Math.floorMod(current.ordinal() + delta, values.length)];
    }

    private TargetPriorityPolicy next(TargetPriorityPolicy current, boolean reverse) {
        TargetPriorityPolicy[] values = TargetPriorityPolicy.values();
        int delta = reverse ? -1 : 1;
        return values[Math.floorMod(current.ordinal() + delta, values.length)];
    }

    private Rectangle stanceBounds() { return new Rectangle(X + 8, Y + 8, BUTTON_W, BUTTON_H); }
    private Rectangle priorityBounds() { return new Rectangle(X + 8 + BUTTON_W + GAP, Y + 8, BUTTON_W, BUTTON_H); }
}
