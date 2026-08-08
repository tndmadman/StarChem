package com.tndmadman.rts;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Role-specific controls for stations that do not have production queues. */
final class StationControlMenu {
    private static final Color BACKGROUND = new Color(10, 20, 30);
    private static final Color PANEL = new Color(18, 37, 53);
    private static final Color TEXT = new Color(230, 242, 250);
    private static final Color MUTED = new Color(155, 180, 196);
    private static final Color ACCENT = new Color(90, 220, 255);

    private StationControlMenu() { }

    static boolean handles(String typeId) { return StationControls.handles(typeId); }

    static boolean showIfHandled(Component invoker, World world, PeerNetwork network, Base base, int x, int y) {
        if (invoker == null || world == null || base == null || !handles(base.typeId)) return false;
        JPopupMenu popup = new JPopupMenu();
        popup.setBorder(BorderFactory.createLineBorder(ACCENT));
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(BACKGROUND);
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel title = label(base.type().name + " CONTROLS", Font.BOLD, 15, TEXT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(title);
        content.add(Box.createVerticalStrut(2));
        JLabel role = label("ROLE: " + StationControls.role(base.typeId).toUpperCase(Locale.ROOT)
                + " | NON-PRODUCTION", Font.PLAIN, 11, MUTED);
        role.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(role);
        content.add(Box.createVerticalStrut(10));

        switch (StationControls.role(base.typeId)) {
            case "radar" -> buildRadar(content, popup, invoker, world, network, base, x, y);
            case "jammer" -> buildJammer(content, base);
            case "decoy" -> buildDecoy(content, popup, invoker, world, network, base, x, y);
            default -> content.add(label("This station has no production queue or configurable controls.",
                    Font.PLAIN, 12, MUTED));
        }

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(BACKGROUND);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.setPreferredSize(new Dimension(430, Math.min(570, Math.max(190, content.getPreferredSize().height + 16))));
        popup.add(scroll);
        popup.show(invoker, Math.max(4, x), Math.max(4, y));
        return true;
    }

    private static void buildRadar(JPanel content, JPopupMenu popup, Component invoker, World world,
                                   PeerNetwork network, Base radar, int x, int y) {
        int range = (int)Math.round(VisibilityRules.baseSensorRange(world, radar));
        IntelWarfareSystem.StructureIntelRule intel = IntelWarfareSystem.rule(radar.typeId);
        int responseCap = Math.max(0, intel.responseShipLimit());
        addInfo(content, "Current mode", IntelWarfareSystem.radarMode(world, radar).name());
        addInfo(content, "Current sensor range", Integer.toString(range));
        addInfo(content, "Miner dispatch cap", Integer.toString(IntelWarfareSystem.dispatchLimit(radar.typeId)));
        if (network != null && network.clientMode()) {
            addInfo(content, "Combat response cap", Integer.toString(responseCap));
        } else {
            int activeResponses = IntelWarfareSystem.radarResponseCount(world, radar.id);
            addInfo(content, "Combat responders", activeResponses + " / " + responseCap);
        }
        addInfo(content, "Combat response radius", Integer.toString((int)Math.round(Math.max(0, intel.responseRadius()))));
        content.add(Box.createVerticalStrut(6));
        JLabel combatNote = label("<html>Guarding owned combat ships respond first; idle owned ships fill remaining capacity.<br>Combat stance and target priority control automatic response.</html>",
                Font.PLAIN, 11, MUTED);
        combatNote.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(combatNote);
        content.add(Box.createVerticalStrut(10));
        content.add(section("RESOURCE PRIORITY"));
        JLabel note = label("Higher entries receive idle miners before lower or unlisted materials.",
                Font.PLAIN, 11, MUTED);
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(note);
        content.add(Box.createVerticalStrut(7));

        List<Material> candidates = StationControls.radarCandidates(world, radar);
        List<Material> priorities = StationControls.radarPriorities(world, radar);
        if (candidates.isEmpty()) {
            content.add(label("No resource materials are currently present in this system.", Font.PLAIN, 12, MUTED));
            return;
        }

        Set<Material> ordered = new LinkedHashSet<>(priorities);
        ordered.addAll(candidates);
        int rank = 1;
        for (Material material : ordered) {
            boolean prioritized = priorities.contains(material);
            content.add(radarMaterialRow(popup, invoker, world, network, radar, x, y,
                    material, prioritized ? rank++ : 0, priorities));
            content.add(Box.createVerticalStrut(4));
        }
        if (!priorities.isEmpty()) {
            JButton clear = actionButton("Clear priority list", () -> send(popup, invoker, world, network, radar,
                    x, y, "RADAR_PRIORITY_CLEAR", ""));
            clear.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(Box.createVerticalStrut(5));
            content.add(clear);
        }
    }

    private static JPanel radarMaterialRow(JPopupMenu popup, Component invoker, World world, PeerNetwork network,
                                           Base radar, int x, int y, Material material, int rank,
                                           List<Material> priorities) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBackground(PANEL);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(rank > 0 ? ACCENT : new Color(70, 92, 108)),
                BorderFactory.createEmptyBorder(5, 7, 5, 7)));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        String prefix = rank > 0 ? rank + ". " : "— ";
        JLabel name = label(prefix + material.label, rank > 0 ? Font.BOLD : Font.PLAIN, 12, TEXT);
        name.setPreferredSize(new Dimension(170, 28));
        row.add(name);
        row.add(Box.createHorizontalGlue());
        if (rank <= 0) {
            row.add(smallButton("Prioritize", () -> send(popup, invoker, world, network, radar,
                    x, y, "RADAR_PRIORITY_TOP", material.name())));
        } else {
            int index = priorities.indexOf(material);
            JButton up = smallButton("↑", () -> send(popup, invoker, world, network, radar,
                    x, y, "RADAR_PRIORITY_UP", material.name()));
            up.setEnabled(index > 0);
            row.add(up);
            row.add(Box.createHorizontalStrut(3));
            JButton down = smallButton("↓", () -> send(popup, invoker, world, network, radar,
                    x, y, "RADAR_PRIORITY_DOWN", material.name()));
            down.setEnabled(index >= 0 && index < priorities.size() - 1);
            row.add(down);
            row.add(Box.createHorizontalStrut(3));
            row.add(smallButton("Remove", () -> send(popup, invoker, world, network, radar,
                    x, y, "RADAR_PRIORITY_REMOVE", material.name())));
        }
        return row;
    }

    private static void buildJammer(JPanel content, Base jammer) {
        addInfo(content, "Jamming radius", Integer.toString((int)Math.round(StationControls.jammerRange(jammer.typeId))));
        addInfo(content, "Sensor suppression", Math.round(StationControls.jammerStrength(jammer.typeId) * 100) + "%");
        addInfo(content, "Emission signature", String.format(Locale.ROOT, "%.2fx",
                StationControls.signatureMultiplier(jammer.typeId)));
        content.add(Box.createVerticalStrut(10));
        content.add(section("JAMMER STATUS"));
        JLabel status = label("The jammer operates continuously while the station is alive and operational.",
                Font.PLAIN, 12, MUTED);
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(status);
    }

    private static void buildDecoy(JPanel content, JPopupMenu popup, Component invoker, World world,
                                   PeerNetwork network, Base decoy, int x, int y) {
        String selected = StationControls.decoySpoofType(world, decoy);
        BaseType current = Rules.findBase(selected);
        addInfo(content, "Current spoof", current == null ? selected : current.name);
        content.add(Box.createVerticalStrut(10));
        content.add(section("SPOOFED STATION SIGNAL"));
        JLabel note = label("The true decoy identity is revealed only by a detailed scan.", Font.PLAIN, 11, MUTED);
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(note);
        content.add(Box.createVerticalStrut(7));

        List<String> profiles = new ArrayList<>(StationControls.decoyProfiles(decoy.typeId));
        if (profiles.isEmpty()) {
            content.add(label("No spoof profiles are configured for this decoy.", Font.PLAIN, 12, MUTED));
            return;
        }
        for (String profile : profiles) {
            BaseType type = Rules.findBase(profile);
            if (type == null || IntelWarfareSystem.isDecoy(profile)) continue;
            JButton button = actionButton((profile.equals(selected) ? "● " : "○ ") + type.name,
                    () -> send(popup, invoker, world, network, decoy, x, y, "DECOY_PROFILE", profile));
            button.setEnabled(!profile.equals(selected));
            button.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(button);
            content.add(Box.createVerticalStrut(4));
        }
    }

    private static void send(JPopupMenu popup, Component invoker, World world, PeerNetwork network,
                             Base base, int x, int y, String action, String value) {
        boolean changed = StationControlCommands.apply(world, base.playerId, base.id, action, value);
        if (!changed) return;
        if (network != null) network.production(base.playerId, "CONTROL", base.id, action, value);
        popup.setVisible(false);
        showIfHandled(invoker, world, network, base, x, y);
    }

    private static void addInfo(JPanel content, String name, String value) {
        JLabel label = label(name + ": " + value, Font.PLAIN, 12, TEXT);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(label);
        content.add(Box.createVerticalStrut(3));
    }

    private static JLabel section(String text) {
        JLabel label = label(text, Font.BOLD, 12, ACCENT);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JLabel label(String text, int style, int size, Color color) {
        JLabel label = new JLabel(text == null ? "" : text, SwingConstants.LEFT);
        label.setForeground(color);
        label.setFont(label.getFont().deriveFont(style, (float)size));
        return label;
    }

    private static JButton actionButton(String text, Runnable action) {
        JButton button = new JButton(text);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setFocusPainted(false);
        button.setForeground(TEXT);
        button.setBackground(PANEL);
        button.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(ACCENT),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)));
        button.addActionListener(event -> action.run());
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        return button;
    }

    private static JButton smallButton(String text, Runnable action) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.setForeground(TEXT);
        button.setBackground(new Color(28, 67, 86));
        button.setBorder(BorderFactory.createEmptyBorder(4, 7, 4, 7));
        button.addActionListener(event -> action.run());
        return button;
    }
}
