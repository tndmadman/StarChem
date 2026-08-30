package com.tndmadman.rts;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class EventHud {
    private static final int MAX_ROWS = 4;

    List<String> lines(World world) {
        if (world == null) return List.of();
        List<GalaxyEventView> views = new ArrayList<>(GalaxyEventDirector.visibleViews(world));
        // On remote clients the combined legacy+advanced V rows already live in
        // GalaxyEventDirector's remote-view registry. On the authoritative world
        // append the local advanced runtime directly.
        views.addAll(GalaxyEventExtensions.viewsFor(world, PlayerRegistry.localId()));
        views.sort(Comparator.comparing(GalaxyEventView::systemId).thenComparing(GalaxyEventView::eventId));

        List<String> out = new ArrayList<>();
        String previousEventId = "";
        for (GalaxyEventView view : views) {
            if (view == null || view.eventId().equals(previousEventId)
                    || !world.activeSystemId().equals(view.systemId())) continue;
            previousEventId = view.eventId();
            int seconds = Math.max(0, (int)Math.ceil(view.remainingSeconds()));
            String phase = view.phase() == GalaxyEventPhase.CLOSING ? "CLOSING" : "ACTIVE";
            out.add(view.name() + " | " + phase + " | " + seconds + "s");
            if (out.size() >= MAX_ROWS) break;
        }
        return List.copyOf(out);
    }

    void draw(Graphics2D source, World world, int screenWidth) {
        if (source == null || world == null || screenWidth <= 0) return;
        List<String> rows = lines(world);
        if (rows.isEmpty()) return;
        Graphics2D g = (Graphics2D) source.create();
        Font old = g.getFont();
        g.setFont(old.deriveFont(Font.BOLD, 11f));
        int width = 330;
        int height = 28 + rows.size() * 18;
        int x = 12;
        int y = 152;
        if (x + width > screenWidth - 12) width = Math.max(220, screenWidth - 24);
        g.setColor(new Color(0, 0, 0, 185));
        g.fillRoundRect(x, y, width, height, 12, 12);
        g.setColor(new Color(120, 205, 235, 195));
        g.drawRoundRect(x, y, width, height, 12, 12);
        g.setColor(new Color(225, 244, 255));
        g.drawString("DISCOVERED EVENTS", x + 12, y + 18);
        g.setFont(old.deriveFont(Font.PLAIN, 10f));
        for (int i = 0; i < rows.size(); i++) {
            g.setColor(new Color(215, 232, 244));
            g.drawString(rows.get(i), x + 12, y + 37 + i * 18);
        }
        g.setFont(old);
        g.dispose();
    }
}
