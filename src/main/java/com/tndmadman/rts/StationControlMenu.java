package com.tndmadman.rts;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Unified station controls, production access, and inter-system logistics routes. */
final class StationControlMenu {
    private static final Color BACKGROUND = new Color(10, 20, 30);
    private static final Color PANEL = new Color(18, 37, 53);
    private static final Color TEXT = new Color(230, 242, 250);
    private static final Color MUTED = new Color(155, 180, 196);
    private static final Color ACCENT = new Color(90, 220, 255);
    private static final BuildMenu PRODUCTION_MENU = new BuildMenu();

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
        boolean nonProduction = StationControls.nonProduction(base.typeId);
        JLabel role = label("ROLE: " + StationControls.role(base.typeId).toUpperCase(Locale.ROOT)
                + (nonProduction ? " | NON-PRODUCTION" : " | PRODUCTION"), Font.PLAIN, 11, MUTED);
        role.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(role);
        content.add(Box.createVerticalStrut(10));

        if (!nonProduction) {
            JButton production = actionButton("OPEN PRODUCTION", () -> {
                popup.setVisible(false);
                PRODUCTION_MENU.showForBase(world, network, base, x, y);
            });
            production.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(production);
            content.add(Box.createVerticalStrut(5));
            JButton policies = actionButton("PRODUCTION POLICIES & TEMPLATES", () -> {
                popup.setVisible(false);
                ProductionPolicyMenu.show(invoker, world, network, base, x, y);
            });
            policies.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(policies);
            content.add(Box.createVerticalStrut(10));
        } else {
            switch (StationControls.role(base.typeId)) {
                case "radar" -> buildRadar(content, popup, invoker, world, network, base, x, y);
                case "jammer" -> buildJammer(content, base);
                case "decoy" -> buildDecoy(content, popup, invoker, world, network, base, x, y);
                default -> content.add(label("This station has no additional role-specific controls.",
                        Font.PLAIN, 12, MUTED));
            }
            content.add(Box.createVerticalStrut(10));
        }

        buildLogistics(content, popup, invoker, world, network, base, x, y);

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(BACKGROUND);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.setPreferredSize(new Dimension(520, Math.min(640, Math.max(230, content.getPreferredSize().height + 16))));
        popup.add(scroll);
        popup.show(invoker, Math.max(4, x), Math.max(4, y));
        return true;
    }

    private static void buildLogistics(JPanel content, JPopupMenu popup, Component invoker, World world,
                                       PeerNetwork network, Base source, int x, int y) {
        content.add(section("INTER-SYSTEM LOGISTICS"));
        JLabel note = label("Routes use real Haulers/Freighters. Select cargo ships and optional armed escorts before creating or editing; no selected transport means automatic assignment.",
                Font.PLAIN, 11, MUTED);
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(note);
        content.add(Box.createVerticalStrut(7));

        List<LogisticsRouteSystem.RouteView> routes = LogisticsRouteSystem.viewsForSource(world, source);
        if (routes.isEmpty()) {
            JLabel empty = label("No routes originate from this station.", Font.PLAIN, 12, MUTED);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(empty);
            content.add(Box.createVerticalStrut(6));
        } else {
            for (LogisticsRouteSystem.RouteView route : routes) {
                content.add(routeRow(popup, invoker, world, network, source, x, y, route));
                content.add(Box.createVerticalStrut(5));
            }
        }

        JButton create = actionButton("CREATE ROUTE", () -> openRouteEditor(
                popup, invoker, world, network, source, x, y, null));
        create.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(create);
    }

    private static JPanel routeRow(JPopupMenu popup, Component invoker, World world, PeerNetwork network,
                                   Base source, int x, int y, LogisticsRouteSystem.RouteView route) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setBackground(PANEL);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(70, 115, 140)),
                BorderFactory.createEmptyBorder(6, 7, 6, 7)));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));

        JLabel title = label(route.id() + " | " + route.phase() + " | "
                + route.destinationSystemId() + "/" + route.destinationBaseId(), Font.BOLD, 11, TEXT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(title);
        String materialText = route.materials().isEmpty() ? "?" : route.materials().stream()
                .map(material -> material.label).reduce((a, b) -> a + ", " + b).orElse("");
        JLabel detail = label("Materials: " + materialText + " | reserve " + whole(route.sourceReserve())
                + " | target " + whole(route.destinationTarget()) + " | batch " + whole(route.batchSize())
                + " | priority " + route.priority() + " | " + route.transportCount() + " transport(s) | "
                + route.escortCount() + " escort(s)", Font.PLAIN, 10, MUTED);
        detail.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(detail);
        row.add(Box.createVerticalStrut(4));

        JPanel actions = new JPanel();
        actions.setOpaque(false);
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
        String pauseAction = route.phase() == LogisticsRouteSystem.RoutePhase.PAUSED
                ? LogisticsRouteSystem.COMMAND_RESUME : LogisticsRouteSystem.COMMAND_PAUSE;
        String pauseLabel = route.phase() == LogisticsRouteSystem.RoutePhase.PAUSED ? "Resume" : "Pause";
        actions.add(smallButton(pauseLabel, () -> sendRoute(popup, invoker, world, network, source,
                x, y, pauseAction, route.id())));
        actions.add(Box.createHorizontalStrut(4));
        actions.add(smallButton("Edit", () -> openRouteEditor(
                popup, invoker, world, network, source, x, y, route)));
        actions.add(Box.createHorizontalStrut(4));
        actions.add(smallButton("Delete", () -> sendRoute(popup, invoker, world, network, source,
                x, y, LogisticsRouteSystem.COMMAND_DELETE, route.id())));
        actions.add(Box.createHorizontalGlue());
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(actions);
        return row;
    }

    private static void openRouteEditor(JPopupMenu popup, Component invoker, World world, PeerNetwork network,
                                        Base source, int x, int y, LogisticsRouteSystem.RouteView existing) {
        List<String> systems = new ArrayList<>();
        for (GalaxyMapSystem system : world.galaxyMapSnapshot().systems()) {
            if (system != null && system.id() != null && !system.id().isBlank()) systems.add(system.id());
        }
        systems.sort(String::compareTo);
        if (systems.isEmpty()) {
            world.status = "No galaxy systems are available for a logistics route.";
            return;
        }

        JComboBox<String> destinationSystem = new JComboBox<>(systems.toArray(String[]::new));
        JTextField destinationBase = new JTextField(existing == null ? "" : existing.destinationBaseId());
        JTextField materials = new JTextField(existing == null ? "IRON" : materialIds(existing.materials()));
        JTextField reserve = new JTextField(existing == null ? "50" : whole(existing.sourceReserve()));
        JTextField target = new JTextField(existing == null ? "250" : whole(existing.destinationTarget()));
        JTextField batch = new JTextField(existing == null ? "250" : whole(existing.batchSize()));
        JSpinner priority = new JSpinner(new SpinnerNumberModel(existing == null ? 50 : existing.priority(), 0, 100, 1));
        JCheckBox clearEscorts = new JCheckBox("Clear escorts instead of keeping them");
        clearEscorts.setOpaque(false);
        clearEscorts.setForeground(TEXT);
        if (existing != null) destinationSystem.setSelectedItem(existing.destinationSystemId());
        else selectDifferentSystem(destinationSystem, world.activeSystemId());

        List<String> selectedTransports = new ArrayList<>();
        List<String> selectedEscorts = new ArrayList<>();
        for (Unit unit : world.selectedUnits()) {
            if (LogisticsRouteSystem.isTransport(unit)) selectedTransports.add(unit.key());
            else if (WeaponRules.armed(world, unit)) selectedEscorts.add(unit.key());
        }
        selectedTransports.sort(String::compareTo);
        selectedEscorts.sort(String::compareTo);

        JPanel fields = new JPanel(new GridLayout(0, 2, 6, 5));
        fields.add(new JLabel("Destination system")); fields.add(destinationSystem);
        fields.add(new JLabel("Destination base ID")); fields.add(destinationBase);
        fields.add(new JLabel("Materials (IDs, comma-separated)")); fields.add(materials);
        fields.add(new JLabel("Minimum source reserve / material")); fields.add(reserve);
        fields.add(new JLabel("Destination target / material")); fields.add(target);
        fields.add(new JLabel("Max shipment batch")); fields.add(batch);
        fields.add(new JLabel("Priority (0-100)")); fields.add(priority);
        fields.add(new JLabel("Selected transports")); fields.add(new JLabel(selectedTransports.isEmpty()
                ? (existing == null ? "AUTO" : "KEEP") : String.join(", ", selectedTransports)));
        fields.add(new JLabel("Selected escorts")); fields.add(new JLabel(selectedEscorts.isEmpty()
                ? (existing == null ? "NONE" : "KEEP") : String.join(", ", selectedEscorts)));
        if (existing != null) {
            fields.add(clearEscorts);
            fields.add(new JLabel("Use to remove all escort assignments"));
        }

        int answer = JOptionPane.showConfirmDialog(invoker, fields,
                existing == null ? "Create inter-system logistics route" : "Edit " + existing.id(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;

        List<Material> parsedMaterials = parseMaterials(materials.getText());
        double sourceReserve = positiveNumber(reserve.getText(), true);
        double destinationTarget = positiveNumber(target.getText(), false);
        double batchSize = positiveNumber(batch.getText(), false);
        String systemId = String.valueOf(destinationSystem.getSelectedItem()).trim();
        String baseId = destinationBase.getText().trim();
        if (parsedMaterials.isEmpty() || sourceReserve < 0 || destinationTarget <= 0 || batchSize <= 0
                || baseId.isBlank()) {
            world.status = "Invalid logistics route settings. Check destination, materials, reserve, target, and batch.";
            return;
        }

        boolean updating = existing != null;
        List<String> transportSpec = selectedTransports;
        List<String> escortSpec = clearEscorts.isSelected() ? List.of() : selectedEscorts;
        boolean keepTransports = updating && selectedTransports.isEmpty();
        boolean keepEscorts = updating && selectedEscorts.isEmpty() && !clearEscorts.isSelected();
        String encoded = LogisticsRouteSystem.encodeSpec(updating ? existing.id() : "",
                systemId, baseId, parsedMaterials, sourceReserve, destinationTarget, batchSize,
                ((Number)priority.getValue()).intValue(), transportSpec, escortSpec,
                keepTransports || keepEscorts);
        if (updating && clearEscorts.isSelected() && keepTransports) {
            encoded = encodeMixedSpec(existing.id(), systemId, baseId, parsedMaterials,
                    sourceReserve, destinationTarget, batchSize, ((Number)priority.getValue()).intValue(),
                    "KEEP", "NONE");
        } else if (updating && keepTransports != keepEscorts) {
            encoded = encodeMixedSpec(existing.id(), systemId, baseId, parsedMaterials,
                    sourceReserve, destinationTarget, batchSize, ((Number)priority.getValue()).intValue(),
                    keepTransports ? "KEEP" : String.join(",", selectedTransports),
                    keepEscorts ? "KEEP" : selectedEscorts.isEmpty() ? "NONE" : String.join(",", selectedEscorts));
        }
        sendRoute(popup, invoker, world, network, source, x, y,
                updating ? LogisticsRouteSystem.COMMAND_UPDATE : LogisticsRouteSystem.COMMAND_CREATE, encoded);
    }

    private static String encodeMixedSpec(String routeId, String destinationSystemId, String destinationBaseId,
                                          List<Material> materials, double reserve, double target, double batch,
                                          int priority, String transports, String escorts) {
        return "v1~" + routeId + '~' + destinationSystemId + '~' + destinationBaseId + '~'
                + materialIds(materials) + '~' + reserve + '~' + target + '~' + batch + '~' + priority
                + '~' + transports + '~' + escorts;
    }

    private static void selectDifferentSystem(JComboBox<String> combo, String sourceSystem) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (!combo.getItemAt(i).equals(sourceSystem)) {
                combo.setSelectedIndex(i);
                return;
            }
        }
    }

    private static List<Material> parseMaterials(String text) {
        if (text == null || text.isBlank()) return List.of();
        LinkedHashSet<Material> out = new LinkedHashSet<>();
        for (String raw : text.split(",")) {
            if (out.size() >= LogisticsRouteSystem.MAX_MATERIALS) return List.of();
            try { out.add(Material.valueOf(raw.trim().toUpperCase(Locale.ROOT))); }
            catch (RuntimeException ignored) { return List.of(); }
        }
        return List.copyOf(out);
    }

    private static String materialIds(List<Material> materials) {
        if (materials == null || materials.isEmpty()) return "";
        return materials.stream().map(Material::name).reduce((a, b) -> a + "," + b).orElse("");
    }

    private static double positiveNumber(String text, boolean zeroAllowed) {
        try {
            double value = Double.parseDouble(text.trim());
            if (!Double.isFinite(value) || value < 0 || !zeroAllowed && value <= 0) return -1;
            return value;
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static void buildRadar(JPanel content, JPopupMenu popup, Component invoker, World world,
                                   PeerNetwork network, Base radar, int x, int y) {
        int range = (int)Math.round(VisibilityRules.baseSensorRange(world, radar));
        int wormholeRange = (int)Math.round(StationControls.wormholeSearchRange(world, radar));
        StationControls.RadarSearchTarget searchTarget = StationControls.radarSearchTarget(world, radar);
        IntelWarfareSystem.StructureIntelRule intel = IntelWarfareSystem.rule(radar.typeId);
        int responseCap = Math.max(0, intel.responseShipLimit());
        addInfo(content, "Current mode", IntelWarfareSystem.radarMode(world, radar).name());
        addInfo(content, "Search target", searchTarget.name());
        addInfo(content, "Current sensor range", Integer.toString(range));
        addInfo(content, "Wormhole search range", Integer.toString(wormholeRange));
        addInfo(content, "Area exploration", searchTarget == StationControls.RadarSearchTarget.AREA ? "ENABLED" : "PAUSED");
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

        content.add(section("SCAN TARGET"));
        JLabel scanNote = label("<html>AREA explores normal fog and surveys resources. WORMHOLES pauses this radar's area exploration/resource survey and searches farther for wormhole signatures.</html>",
                Font.PLAIN, 11, MUTED);
        scanNote.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(scanNote);
        content.add(Box.createVerticalStrut(7));
        JButton area = actionButton((searchTarget == StationControls.RadarSearchTarget.AREA ? "● " : "○ ") + "AREA SCAN",
                () -> send(popup, invoker, world, network, radar, x, y,
                        "RADAR_SEARCH_TARGET", StationControls.RadarSearchTarget.AREA.name()));
        area.setEnabled(searchTarget != StationControls.RadarSearchTarget.AREA);
        area.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(area);
        content.add(Box.createVerticalStrut(4));
        JButton wormholes = actionButton((searchTarget == StationControls.RadarSearchTarget.WORMHOLES ? "● " : "○ ")
                        + "WORMHOLE SEARCH",
                () -> send(popup, invoker, world, network, radar, x, y,
                        "RADAR_SEARCH_TARGET", StationControls.RadarSearchTarget.WORMHOLES.name()));
        wormholes.setEnabled(searchTarget != StationControls.RadarSearchTarget.WORMHOLES);
        wormholes.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(wormholes);
        content.add(Box.createVerticalStrut(10));

        content.add(section("RESOURCE PRIORITY"));
        JLabel note = label(searchTarget == StationControls.RadarSearchTarget.WORMHOLES
                        ? "Resource survey is paused while this radar searches for wormholes; priorities are retained."
                        : "Higher entries receive idle miners before lower or unlisted materials.",
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

    private static void sendRoute(JPopupMenu popup, Component invoker, World world, PeerNetwork network,
                                  Base base, int x, int y, String action, String value) {
        boolean changed;
        if (network != null && network.clientMode()) {
            network.production(base.playerId, "CONTROL", base.id, action, value);
            world.status = "Requested logistics route update from server.";
            changed = true;
        } else {
            changed = StationControlCommands.apply(world, base.playerId, base.id, action, value);
        }
        if (!changed) {
            world.status = "Logistics route update was rejected.";
            return;
        }
        popup.setVisible(false);
        if (network == null || !network.clientMode()) showIfHandled(invoker, world, network, base, x, y);
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

    private static String whole(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.000001) return Long.toString(Math.round(value));
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
