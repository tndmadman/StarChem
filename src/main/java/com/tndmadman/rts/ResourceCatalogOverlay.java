package com.tndmadman.rts;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ResourceCatalogOverlay extends JComponent implements MouseListener, MouseWheelListener {
    private static final int ROW_HEIGHT = 42;
    private static final int SYSTEM_ROW_HEIGHT = 48;
    private static final int MARGIN = 38;

    private final GamePanel returnFocus;
    private final World world;
    private final List<ResourceSystemCatalog.Entry> entries = ResourceSystemCatalog.entries();
    private int selectedIndex;
    private int resourceScroll;
    private int systemScroll;
    private String previousStatus = "";

    ResourceCatalogOverlay(GamePanel returnFocus, World world) {
        this.returnFocus = returnFocus;
        this.world = world;
        setOpaque(false);
        setVisible(false);
        setFocusable(true);
        addMouseListener(this);
        addMouseWheelListener(this);
        installKeyBindings();
    }

    void toggle() {
        if (isVisible()) close(); else open();
    }

    private void open() {
        if (isVisible()) return;
        previousStatus = world.status;
        world.status = "Resource catalog open. Select a material to view its system types; press I or Escape to close.";
        setVisible(true);
        ensureSelectedVisible();
        repaint();
        SwingUtilities.invokeLater(this::requestFocusInWindow);
    }

    private void close() {
        if (!isVisible()) return;
        setVisible(false);
        if (world.status.startsWith("Resource catalog open.")) world.status = previousStatus;
        returnFocus.requestFocusInWindow();
        returnFocus.repaint();
    }

    private void installKeyBindings() {
        bind(KeyEvent.VK_ESCAPE, "close", this::close);
        bind(KeyEvent.VK_UP, "previous-resource", () -> moveSelection(-1));
        bind(KeyEvent.VK_DOWN, "next-resource", () -> moveSelection(1));
        bind(KeyEvent.VK_PAGE_UP, "previous-page", () -> moveSelection(-Math.max(1, overlayLayout().visibleResourceRows())));
        bind(KeyEvent.VK_PAGE_DOWN, "next-page", () -> moveSelection(Math.max(1, overlayLayout().visibleResourceRows())));
        bind(KeyEvent.VK_HOME, "first-resource", () -> select(0));
        bind(KeyEvent.VK_END, "last-resource", () -> select(entries.size() - 1));
        bind(KeyEvent.VK_LEFT, "systems-up", () -> scrollSystems(-1));
        bind(KeyEvent.VK_RIGHT, "systems-down", () -> scrollSystems(1));
    }

    private void bind(int keyCode, String name, Runnable action) {
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(keyCode, 0), name);
        getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { action.run(); }
        });
    }

    private void moveSelection(int delta) {
        if (entries.isEmpty()) return;
        select(Math.max(0, Math.min(entries.size() - 1, selectedIndex + delta)));
    }

    private void select(int index) {
        if (entries.isEmpty()) return;
        int normalized = Math.max(0, Math.min(entries.size() - 1, index));
        if (normalized != selectedIndex) systemScroll = 0;
        selectedIndex = normalized;
        ensureSelectedVisible();
        repaint();
    }

    private void ensureSelectedVisible() {
        OverlayLayout layout = overlayLayout();
        int rows = Math.max(1, layout.visibleResourceRows());
        if (selectedIndex < resourceScroll) resourceScroll = selectedIndex;
        if (selectedIndex >= resourceScroll + rows) resourceScroll = selectedIndex - rows + 1;
        resourceScroll = clamp(resourceScroll, 0, Math.max(0, entries.size() - rows));
        clampSystemScroll(layout);
    }

    private void scrollResources(int amount) {
        OverlayLayout layout = overlayLayout();
        int max = Math.max(0, entries.size() - layout.visibleResourceRows());
        resourceScroll = clamp(resourceScroll + amount, 0, max);
        repaint();
    }

    private void scrollSystems(int amount) {
        OverlayLayout layout = overlayLayout();
        systemScroll = clamp(systemScroll + amount, 0, maxSystemScroll(layout));
        repaint();
    }

    private void clampSystemScroll(OverlayLayout layout) {
        systemScroll = clamp(systemScroll, 0, maxSystemScroll(layout));
    }

    private int maxSystemScroll(OverlayLayout layout) {
        if (entries.isEmpty()) return 0;
        int systems = entries.get(selectedIndex).systems().size();
        return Math.max(0, systems - layout.visibleSystemRows());
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        OverlayLayout layout = overlayLayout();
        clampSystemScroll(layout);

        g.setColor(new Color(2, 5, 10, 224));
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(new Color(10, 18, 30, 246));
        g.fillRoundRect(layout.panelX(), layout.panelY(), layout.panelWidth(), layout.panelHeight(), 24, 24);
        g.setColor(new Color(92, 137, 180, 170));
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(layout.panelX(), layout.panelY(), layout.panelWidth(), layout.panelHeight(), 24, 24);

        drawHeader(g, layout);
        drawResourceList(g, layout);
        drawDetails(g, layout);
        drawFooter(g, layout);
        g.dispose();
    }

    private void drawHeader(Graphics2D g, OverlayLayout layout) {
        int x = layout.panelX() + 28;
        int y = layout.panelY() + 38;
        g.setFont(g.getFont().deriveFont(Font.BOLD, 22f));
        g.setColor(new Color(230, 244, 255));
        g.drawString("RESOURCE CATALOG", x, y);
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 12f));
        g.setColor(new Color(185, 211, 235));
        g.drawString(entries.size() + " loaded materials | " + ResourceSystemCatalog.systemTemplateCount()
                + " loaded system templates | system type is shown by configured role", x, y + 23);
    }

    private void drawResourceList(Graphics2D g, OverlayLayout layout) {
        g.setColor(new Color(6, 11, 19, 225));
        g.fillRoundRect(layout.listX(), layout.contentY(), layout.listWidth(), layout.contentHeight(), 14, 14);
        g.setColor(new Color(66, 96, 126, 150));
        g.drawRoundRect(layout.listX(), layout.contentY(), layout.listWidth(), layout.contentHeight(), 14, 14);

        g.setFont(g.getFont().deriveFont(Font.BOLD, 12f));
        g.setColor(new Color(208, 229, 247));
        g.drawString("LOADED RESOURCES", layout.listX() + 14, layout.contentY() + 23);

        int first = resourceScroll;
        int last = Math.min(entries.size(), first + layout.visibleResourceRows());
        int rowTop = layout.resourceRowsY();
        for (int index = first; index < last; index++) {
            ResourceSystemCatalog.Entry entry = entries.get(index);
            int y = rowTop + (index - first) * ROW_HEIGHT;
            if (index == selectedIndex) {
                g.setColor(new Color(38, 89, 122, 230));
                g.fillRoundRect(layout.listX() + 7, y + 2, layout.listWidth() - 14, ROW_HEIGHT - 4, 9, 9);
                g.setColor(new Color(112, 205, 255, 210));
                g.drawRoundRect(layout.listX() + 7, y + 2, layout.listWidth() - 14, ROW_HEIGHT - 4, 9, 9);
            }

            g.setColor(entry.material().color);
            g.fillOval(layout.listX() + 17, y + 12, 16, 16);
            g.setColor(new Color(245, 250, 255, 210));
            g.drawOval(layout.listX() + 17, y + 12, 16, 16);

            g.setFont(g.getFont().deriveFont(Font.BOLD, 13f));
            g.setColor(Color.WHITE);
            g.drawString(entry.material().label, layout.listX() + 43, y + 17);
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 10f));
            g.setColor(new Color(177, 204, 228));
            g.drawString(title(entry.material().family.name()) + " | " + title(entry.material().tier.name())
                    + " | " + entry.sourceLabel(), layout.listX() + 43, y + 32);
        }

        if (entries.size() > layout.visibleResourceRows()) {
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 10f));
            g.setColor(new Color(160, 190, 216));
            g.drawString((first + 1) + "-" + last + " of " + entries.size(),
                    layout.listX() + layout.listWidth() - 74, layout.contentY() + 23);
        }
    }

    private void drawDetails(Graphics2D g, OverlayLayout layout) {
        g.setColor(new Color(6, 11, 19, 225));
        g.fillRoundRect(layout.detailX(), layout.contentY(), layout.detailWidth(), layout.contentHeight(), 14, 14);
        g.setColor(new Color(66, 96, 126, 150));
        g.drawRoundRect(layout.detailX(), layout.contentY(), layout.detailWidth(), layout.contentHeight(), 14, 14);
        if (entries.isEmpty()) return;

        ResourceSystemCatalog.Entry entry = entries.get(selectedIndex);
        int x = layout.detailX() + 20;
        int y = layout.contentY() + 34;
        g.setColor(entry.material().color);
        g.fillOval(x, y - 19, 22, 22);
        g.setColor(new Color(245, 250, 255, 220));
        g.drawOval(x, y - 19, 22, 22);
        g.setFont(g.getFont().deriveFont(Font.BOLD, 21f));
        g.setColor(Color.WHITE);
        g.drawString(entry.material().label, x + 34, y);

        g.setFont(g.getFont().deriveFont(Font.PLAIN, 12f));
        g.setColor(new Color(185, 211, 235));
        g.drawString("Family: " + title(entry.material().family.name())
                + "   Rarity: " + title(entry.material().tier.name())
                + "   Source: " + entry.sourceLabel(), x, y + 25);

        int systemsTitleY = y + 62;
        g.setFont(g.getFont().deriveFont(Font.BOLD, 12f));
        g.setColor(new Color(208, 229, 247));
        g.drawString("AVAILABLE IN SYSTEM TYPES", x, systemsTitleY);

        if (entry.systems().isEmpty()) {
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 13f));
            g.setColor(new Color(190, 208, 224));
            String message = entry.material().family == MaterialFamily.SALVAGE
                    ? "This material is obtained from salvage and is not placed in natural resource belts."
                    : "This material is manufactured from other resources and is not placed in natural resource belts.";
            drawWrapped(g, message, x, systemsTitleY + 30, layout.detailWidth() - 40, 19);
            return;
        }

        int first = systemScroll;
        int last = Math.min(entry.systems().size(), first + layout.visibleSystemRows());
        int rowY = systemsTitleY + 16;
        for (int index = first; index < last; index++) {
            ResourceSystemCatalog.SystemAvailability system = entry.systems().get(index);
            int top = rowY + (index - first) * SYSTEM_ROW_HEIGHT;
            g.setColor(new Color(20, 35, 50, 220));
            g.fillRoundRect(x, top, layout.detailWidth() - 40, SYSTEM_ROW_HEIGHT - 5, 9, 9);
            g.setColor(new Color(60, 91, 120, 135));
            g.drawRoundRect(x, top, layout.detailWidth() - 40, SYSTEM_ROW_HEIGHT - 5, 9, 9);

            g.setFont(g.getFont().deriveFont(Font.BOLD, 13f));
            g.setColor(new Color(236, 246, 255));
            g.drawString(system.systemName() + "  [" + title(system.role()) + "]", x + 12, top + 17);
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 10f));
            g.setColor(new Color(171, 202, 228));
            g.drawString(system.systemId() + " | " + nodeKinds(system), x + 12, top + 34);
        }

        if (entry.systems().size() > layout.visibleSystemRows()) {
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 10f));
            g.setColor(new Color(160, 190, 216));
            g.drawString("Showing " + (first + 1) + "-" + last + " of " + entry.systems().size()
                    + " systems | scroll over this panel", x, layout.contentY() + layout.contentHeight() - 13);
        }
    }

    private void drawFooter(Graphics2D g, OverlayLayout layout) {
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
        g.setColor(new Color(185, 211, 235));
        g.drawString("Up/Down: select resource   Left/Right: scroll systems   Mouse wheel: scroll panel   I or Esc: close",
                layout.panelX() + 28, layout.panelY() + layout.panelHeight() - 18);
    }

    private void drawWrapped(Graphics2D g, String text, int x, int y, int width, int lineHeight) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (!current.isEmpty() && g.getFontMetrics().stringWidth(candidate) > width) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) lines.add(current.toString());
        for (int i = 0; i < lines.size(); i++) g.drawString(lines.get(i), x, y + i * lineHeight);
    }

    private String nodeKinds(ResourceSystemCatalog.SystemAvailability system) {
        List<String> labels = new ArrayList<>();
        for (NodeKind kind : system.nodeKinds()) labels.add(title(kind.name()));
        return labels.isEmpty() ? "Unspecified node type" : String.join(", ", labels);
    }

    private String title(String value) {
        if (value == null || value.isBlank()) return "Standard";
        String[] parts = value.toLowerCase(Locale.ROOT).split("[_\\s-]+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    private OverlayLayout overlayLayout() {
        int panelX = MARGIN;
        int panelY = 42;
        int panelWidth = Math.max(1, getWidth() - MARGIN * 2);
        int panelHeight = Math.max(1, getHeight() - 84);
        int contentY = panelY + 78;
        int contentHeight = Math.max(120, panelHeight - 116);
        int gap = 18;
        int listWidth = Math.min(390, Math.max(300, (int)Math.round(panelWidth * 0.36)));
        int listX = panelX + 24;
        int detailX = listX + listWidth + gap;
        int detailWidth = Math.max(260, panelX + panelWidth - 24 - detailX);
        int resourceRowsY = contentY + 32;
        int visibleResourceRows = Math.max(1, (contentHeight - 42) / ROW_HEIGHT);
        int visibleSystemRows = Math.max(1, (contentHeight - 130) / SYSTEM_ROW_HEIGHT);
        return new OverlayLayout(panelX, panelY, panelWidth, panelHeight, contentY, contentHeight,
                listX, listWidth, detailX, detailWidth, resourceRowsY, visibleResourceRows, visibleSystemRows);
    }

    private int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    @Override public void mousePressed(MouseEvent e) {
        requestFocusInWindow();
        OverlayLayout layout = overlayLayout();
        if (e.getX() < layout.listX() || e.getX() > layout.listX() + layout.listWidth()) return;
        if (e.getY() < layout.resourceRowsY()) return;
        int row = (e.getY() - layout.resourceRowsY()) / ROW_HEIGHT;
        if (row < 0 || row >= layout.visibleResourceRows()) return;
        int index = resourceScroll + row;
        if (index < entries.size()) select(index);
    }

    @Override public void mouseWheelMoved(MouseWheelEvent e) {
        OverlayLayout layout = overlayLayout();
        int amount = e.getWheelRotation();
        if (e.getX() >= layout.detailX()) scrollSystems(amount);
        else scrollResources(amount);
    }

    @Override public void mouseClicked(MouseEvent e) { }
    @Override public void mouseReleased(MouseEvent e) { }
    @Override public void mouseEntered(MouseEvent e) { }
    @Override public void mouseExited(MouseEvent e) { }

    private record OverlayLayout(
            int panelX, int panelY, int panelWidth, int panelHeight,
            int contentY, int contentHeight,
            int listX, int listWidth, int detailX, int detailWidth,
            int resourceRowsY, int visibleResourceRows, int visibleSystemRows
    ) { }
}
