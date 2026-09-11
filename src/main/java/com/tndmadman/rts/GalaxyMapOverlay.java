package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JOptionPane;

final class GalaxyMapOverlay {
    private long selectedFleetId;

    void draw(Graphics2D g2, GalaxyMapSnapshot snapshot, int width, int height) {
        snapshot = visibleSnapshot(snapshot);
        Graphics2D g = (Graphics2D) g2.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(UiPalette.OVERLAY);
        g.fillRect(0, 0, width, height);

        g.setColor(UiPalette.PANEL);
        g.fillRoundRect(38, 42, Math.max(1, width - 76), Math.max(1, height - 84), 24, 24);
        g.setColor(UiPalette.BORDER);
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(38, 42, Math.max(1, width - 76), Math.max(1, height - 84), 24, 24);

        g.setFont(g.getFont().deriveFont(Font.BOLD, 22f));
        g.setColor(UiPalette.TEXT);
        g.drawString("GALAXY MAP", 66, 78);
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 12f));
        g.setColor(UiPalette.TEXT_MUTED);
        FleetView selected = selectedFleet();
        if (selected == null) {
            g.drawString("Click system to view | Click gold FLEET label to select a persistent fleet | Ring color shows controller", 66, 101);
        } else {
            g.drawString("Selected fleet: " + selected.name() + " (#" + selected.fleetId()
                    + ") | Click a target system for Move/Rally or View", 66, 101);
        }

        if (snapshot == null || snapshot.empty()) {
            g.setColor(UiPalette.TEXT_MUTED);
            g.drawString("No active systems discovered yet.", 66, 134);
            g.dispose();
            return;
        }

        Map<String, NodeLayout> layout = layout(snapshot, width, height);
        drawGrid(g, width, height);
        drawLinks(g, snapshot, layout);
        drawNodes(g, snapshot, layout);
        drawLegend(g, width, height);
        g.dispose();
    }

    String systemAt(GalaxyMapSnapshot snapshot, int screenX, int screenY, int width, int height) {
        snapshot = visibleSnapshot(snapshot);
        if (snapshot == null || snapshot.empty()) return "";
        Map<String, NodeLayout> layout = layout(snapshot, width, height);
        int count = snapshot.systems().size();
        World world = PlayerRegistry.activeWorld();
        String owner = PlayerRegistry.localId();

        if (world != null && owner != null && !owner.isBlank()) {
            for (GalaxyMapSystem system : snapshot.systems()) {
                NodeLayout node = layout.get(system.id());
                if (node == null) continue;
                double radius = nodeRadius(count, system.active());
                if (fleetLabelHit(world, owner, system.id(), node, radius, screenX, screenY)) {
                    selectFleetAtSystem(world, owner, system.id());
                    return "";
                }
            }
        }

        for (GalaxyMapSystem system : snapshot.systems()) {
            NodeLayout node = layout.get(system.id());
            if (node == null) continue;
            double radius = nodeRadius(count, system.active());
            if (Point2D.distance(screenX, screenY, node.x, node.y) > radius + 10) continue;
            if (selectedFleetId > 0 && world != null && owner != null && !owner.isBlank()) {
                return handleSelectedFleetTarget(world, owner, system.id());
            }
            return system.id();
        }
        return "";
    }

    Point2D pointForSystem(GalaxyMapSnapshot snapshot, String systemId, int width, int height) {
        snapshot = visibleSnapshot(snapshot);
        if (snapshot == null || snapshot.empty() || systemId == null || systemId.isBlank()) return null;
        NodeLayout node = layout(snapshot, width, height).get(systemId);
        return node == null ? null : new Point2D.Double(node.x, node.y);
    }

    long selectFleetAtSystemForTest(World world, String ownerId, String systemId) {
        List<FleetView> fleets = GalaxyMapFleetCommandBridge.fleetsAtSystem(world, ownerId, systemId);
        selectedFleetId = fleets.isEmpty() ? 0 : fleets.get(0).fleetId();
        return selectedFleetId;
    }

    GalaxyMapFleetCommandBridge.DispatchResult issueSelectedFleetForTest(
            World world, String ownerId, String targetSystemId, GalaxyMapFleetCommandBridge.Action action) {
        return GalaxyMapFleetCommandBridge.dispatch(world, ownerId, selectedFleetId, targetSystemId, action);
    }

    long selectedFleetIdForTest() { return selectedFleetId; }

    private boolean selectFleetAtSystem(World world, String ownerId, String systemId) {
        List<FleetView> fleets = GalaxyMapFleetCommandBridge.fleetsAtSystem(world, ownerId, systemId);
        if (fleets.isEmpty()) return false;
        FleetView chosen;
        if (fleets.size() == 1 || GraphicsEnvironment.isHeadless()) {
            chosen = fleets.get(0);
        } else {
            String[] labels = new String[fleets.size()];
            for (int i = 0; i < fleets.size(); i++) {
                FleetView fleet = fleets.get(i);
                labels[i] = fleet.name() + " (#" + fleet.fleetId() + ") — " + fleet.livingShips() + " ships";
            }
            Object selected = JOptionPane.showInputDialog(null,
                    "Select an owned persistent fleet to command from the Galaxy Map:",
                    "Galaxy Fleet Command", JOptionPane.PLAIN_MESSAGE, null, labels, labels[0]);
            if (selected == null) return false;
            int index = 0;
            for (int i = 0; i < labels.length; i++) if (labels[i].equals(selected)) { index = i; break; }
            chosen = fleets.get(index);
        }
        selectedFleetId = chosen.fleetId();
        world.status = "Galaxy fleet selected: " + chosen.name() + " (#" + chosen.fleetId()
                + "). Click a target system for Move or Rally.";
        return true;
    }

    private String handleSelectedFleetTarget(World world, String ownerId, String targetSystemId) {
        FleetView selected = FleetManager.view(world, ownerId, selectedFleetId).orElse(null);
        if (selected == null) {
            selectedFleetId = 0;
            world.status = "Selected fleet is no longer available.";
            return "";
        }
        if (GraphicsEnvironment.isHeadless()) return targetSystemId;
        Object[] options = {"Move", "Rally", "View System", "Cancel"};
        int choice = JOptionPane.showOptionDialog(null,
                selected.name() + " (#" + selected.fleetId() + ") -> " + systemDisplayName(targetSystemId),
                "Galaxy Fleet Order", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE,
                null, options, options[0]);
        if (choice == 2) {
            selectedFleetId = 0;
            return targetSystemId;
        }
        if (choice != 0 && choice != 1) return "";
        GalaxyMapFleetCommandBridge.Action action = choice == 1
                ? GalaxyMapFleetCommandBridge.Action.RALLY : GalaxyMapFleetCommandBridge.Action.MOVE;
        GalaxyMapFleetCommandBridge.DispatchResult result =
                GalaxyMapFleetCommandBridge.dispatch(world, ownerId, selected.fleetId(), targetSystemId, action);
        world.status = result.message();
        if (result.submitted()) selectedFleetId = 0;
        return "";
    }

    private boolean fleetLabelHit(World world, String ownerId, String systemId, NodeLayout node,
                                  double radius, int screenX, int screenY) {
        if (GalaxyMapFleetCommandBridge.fleetsAtSystem(world, ownerId, systemId).isEmpty()) return false;
        double labelY = node.y + radius + 39;
        return Math.abs(screenX - node.x) <= 62 && Math.abs(screenY - labelY) <= 10;
    }

    private FleetView selectedFleet() {
        if (selectedFleetId <= 0) return null;
        World world = PlayerRegistry.activeWorld();
        String owner = PlayerRegistry.localId();
        if (world == null || owner == null || owner.isBlank()) return null;
        FleetView fleet = FleetManager.view(world, owner, selectedFleetId).orElse(null);
        if (fleet == null) selectedFleetId = 0;
        return fleet;
    }

    private String systemDisplayName(String systemId) {
        StarSystemDefinition definition = StarSystems.get(GalaxySystemIdentity.templateId(systemId));
        return definition == null ? systemId : definition.name() + " (" + systemId + ")";
    }

    private GalaxyMapSnapshot visibleSnapshot(GalaxyMapSnapshot snapshot) {
        if (snapshot == null || snapshot.empty()) return snapshot;
        Set<String> linkedSystemIds = new LinkedHashSet<>();
        if (snapshot.links() != null) {
            for (GalaxyMapLink link : snapshot.links()) {
                linkedSystemIds.add(link.fromSystemId());
                linkedSystemIds.add(link.toSystemId());
            }
        }
        List<GalaxyMapSystem> visibleSystems = new ArrayList<>();
        Set<String> visibleIds = new LinkedHashSet<>();
        for (GalaxyMapSystem system : snapshot.systems()) {
            if (isVisibleSystem(system, linkedSystemIds)) {
                visibleSystems.add(system);
                visibleIds.add(system.id());
            }
        }
        List<GalaxyMapLink> visibleLinks = new ArrayList<>();
        if (snapshot.links() != null) {
            for (GalaxyMapLink link : snapshot.links()) {
                if (visibleIds.contains(link.fromSystemId()) && visibleIds.contains(link.toSystemId())) visibleLinks.add(link);
            }
        }
        return new GalaxyMapSnapshot(snapshot.activeSystemId(), List.copyOf(visibleSystems), List.copyOf(visibleLinks));
    }

    private boolean isVisibleSystem(GalaxyMapSystem system, Set<String> linkedSystemIds) {
        if (system == null) return false;
        if (system.staticSystem() || linkedSystemIds.contains(system.id())) return true;
        if (system.home() || system.special()) return true;
        return system.ships() > 0 || system.bases() > 0 || system.hasLocalAssets();
    }

    private void drawGrid(Graphics2D g, int width, int height) {
        g.setColor(new Color(70, 72, 70, 55));
        for (int x = 70; x < width - 70; x += 88) g.drawLine(x, 116, x, height - 72);
        for (int y = 120; y < height - 72; y += 88) g.drawLine(52, y, width - 52, y);
    }

    private void drawLinks(Graphics2D g, GalaxyMapSnapshot snapshot, Map<String, NodeLayout> layout) {
        Set<String> temporary = discoveredTemporaryLinkKeys();
        Stroke normal = new BasicStroke(1.7f);
        Stroke temporaryStroke = new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                10f, new float[]{7f, 6f}, 0f);
        for (GalaxyMapLink link : snapshot.links()) {
            NodeLayout a = layout.get(link.fromSystemId());
            NodeLayout b = layout.get(link.toSystemId());
            if (a == null || b == null) continue;
            boolean dynamic = temporary.contains(linkKey(link.fromSystemId(), link.toSystemId()));
            g.setStroke(dynamic ? temporaryStroke : normal);
            // Wormhole links stay cool/emissive because they represent actual energy.
            g.setColor(dynamic ? new Color(105, 235, 255, 185) : new Color(90, 186, 255, 92));
            g.draw(new Line2D.Double(a.x, a.y, b.x, b.y));
        }
        g.setStroke(normal);
    }

    private void drawNodes(Graphics2D g, GalaxyMapSnapshot snapshot, Map<String, NodeLayout> layout) {
        int count = snapshot.systems().size();
        Map<String,List<GalaxyEventView>> eventsBySystem = discoveredEventsBySystem();
        Map<String,Integer> fleetsBySystem = localFleetCounts();
        FleetView selected = selectedFleet();
        for (GalaxyMapSystem system : snapshot.systems()) {
            NodeLayout node = layout.get(system.id());
            if (node == null) continue;
            double radius = nodeRadius(count, system.active());
            Ellipse2D circle = new Ellipse2D.Double(node.x - radius, node.y - radius, radius * 2, radius * 2);

            Color fill = system.home() ? new Color(52, 57, 56, 235)
                    : system.special() ? new Color(63, 53, 48, 240)
                    : new Color(40, 45, 47, 235);
            if (system.active()) fill = new Color(76, 66, 49, 245);
            g.setColor(fill);
            g.fill(circle);

            Color controlColor = new Color(system.controlColorRgb() & 0xFFFFFF);
            g.setStroke(new BasicStroke(system.active() ? 4.5f : 3.2f));
            g.setColor(controlColor);
            g.draw(circle);

            if (system.hasLocalAssets()) {
                double innerRadius = Math.max(6, radius - 6);
                g.setStroke(new BasicStroke(2f));
                g.setColor(new Color(255, 236, 150));
                g.draw(new Ellipse2D.Double(node.x - innerRadius, node.y - innerRadius, innerRadius * 2, innerRadius * 2));
            }

            int titleSize = count > 20 ? 10 : 12;
            int detailSize = count > 20 ? 9 : 10;
            g.setFont(g.getFont().deriveFont(Font.BOLD, (float)titleSize));
            g.setColor(UiPalette.TEXT);
            drawCentered(g, system.name(), node.x, node.y - radius - 10);
            g.setFont(g.getFont().deriveFont(Font.PLAIN, (float)detailSize));
            g.setColor(UiPalette.TEXT_MUTED);
            drawCentered(g, system.id(), node.x, node.y + 3);
            drawCentered(g, system.ships() + "S  " + system.bases() + "B  " + system.resources() + "R", node.x, node.y + radius + 13);
            g.setColor(controlColor);
            drawCentered(g, system.controlLabel(), node.x, node.y + radius + 26);
            int fleetCount = fleetsBySystem.getOrDefault(system.id(), 0);
            if (fleetCount > 0) {
                g.setFont(g.getFont().deriveFont(Font.BOLD, (float)Math.max(8, detailSize - 1)));
                g.setColor(new Color(255, 236, 150));
                String fleetLabel = fleetCount + (fleetCount == 1 ? " FLEET" : " FLEETS");
                if (selected != null && selected.shipsBySystem().getOrDefault(system.id(), 0) > 0) {
                    fleetLabel += " [SELECTED]";
                }
                drawCentered(g, fleetLabel, node.x, node.y + radius + 39);
            }
            List<GalaxyEventView> events = eventsBySystem.getOrDefault(system.id(), List.of());
            if (!events.isEmpty()) {
                GalaxyEventView first = events.get(0);
                g.setFont(g.getFont().deriveFont(Font.BOLD, (float)Math.max(8, detailSize - 1)));
                GalaxyEventVisualStyle visual = GalaxyEventVisualCatalog.visual(first.definitionId());
                int eventRgb = visual.enabled() ? visual.mapColorRgb() : 0xEBC3FF;
                int eventAlpha = 255;
                if (visual.enabled() && visual.mapPulse() && visual.mapPulseSpeed() > 0) {
                    double pulse = 0.5 + 0.5 * Math.sin(System.nanoTime() / 1_000_000_000.0
                            * visual.mapPulseSpeed() * Math.PI * 2.0);
                    eventAlpha = 145 + (int)Math.round(pulse * 110);
                    double ring = radius + 7 + pulse * 5;
                    g.setStroke(new BasicStroke(1.8f));
                    g.setColor(new Color((eventRgb >> 16) & 0xFF, (eventRgb >> 8) & 0xFF, eventRgb & 0xFF, eventAlpha));
                    g.draw(new Ellipse2D.Double(node.x - ring, node.y - ring, ring * 2, ring * 2));
                }
                g.setColor(new Color((eventRgb >> 16) & 0xFF, (eventRgb >> 8) & 0xFF, eventRgb & 0xFF, eventAlpha));
                String eventLine = first.name() + " | " + first.phase().name() + " | "
                        + Math.max(0, (int)Math.ceil(first.remainingSeconds())) + "s";
                if (events.size() > 1) eventLine += " +" + (events.size() - 1);
                drawCentered(g, eventLine, node.x, node.y - radius - 23);
            }
        }
    }

    private Map<String,Integer> localFleetCounts() {
        Map<String,Integer> counts = new HashMap<>();
        World world = PlayerRegistry.activeWorld();
        String owner = PlayerRegistry.localId();
        if (world == null || owner == null || owner.isBlank()) return counts;
        for (FleetView fleet : FleetManager.viewsForOwner(world, owner)) {
            for (Map.Entry<String,Integer> entry : fleet.shipsBySystem().entrySet()) {
                if (entry.getValue() != null && entry.getValue() > 0) counts.merge(entry.getKey(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private Map<String,List<GalaxyEventView>> discoveredEventsBySystem() {
        Map<String,List<GalaxyEventView>> grouped = new HashMap<>();
        World world = PlayerRegistry.activeWorld();
        if (world == null) return grouped;
        for (GalaxyEventView view : GalaxyEventDirector.visibleViews(world)) {
            if (view == null || view.systemId() == null || view.systemId().isBlank()) continue;
            grouped.computeIfAbsent(view.systemId(), ignored -> new ArrayList<>()).add(view);
        }
        for (GalaxyEventView view : GalaxyEventExtensions.viewsFor(world, PlayerRegistry.localId())) {
            if (view == null || view.systemId() == null || view.systemId().isBlank()) continue;
            List<GalaxyEventView> rows = grouped.computeIfAbsent(view.systemId(), ignored -> new ArrayList<>());
            boolean duplicate = rows.stream().anyMatch(existing -> existing.eventId().equals(view.eventId()));
            if (!duplicate) rows.add(view);
        }
        return grouped;
    }

    private Set<String> discoveredTemporaryLinkKeys() {
        Set<String> keys = new LinkedHashSet<>();
        World world = PlayerRegistry.activeWorld();
        if (world == null) return keys;
        for (GalaxyMapLink link : GalaxyEventDirector.temporaryLinksFor(world, PlayerRegistry.localId())) {
            if (link != null) keys.add(linkKey(link.fromSystemId(), link.toSystemId()));
        }
        return keys;
    }

    private String linkKey(String first, String second) {
        String a = first == null ? "" : first;
        String b = second == null ? "" : second;
        return a.compareTo(b) <= 0 ? a + "\u0000" + b : b + "\u0000" + a;
    }

    private void drawLegend(Graphics2D g, int width, int height) {
        int x = 66;
        int y = Math.max(136, height - 50);
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
        g.setColor(UiPalette.TEXT_MUTED);
        g.drawString("Outer ring = controller/claimant   Gold FLEET label = selectable persistent fleet   Gold inner ring = your assets   Dashed cyan link = temporary shortcut", x, y);
    }

    private double nodeRadius(int count, boolean active) {
        double base = count > 24 ? 20 : count > 16 ? 24 : count > 10 ? 29 : 34;
        return active ? base + 5 : base;
    }

    private void drawCentered(Graphics2D g, String text, double cx, double y) {
        String safe = text == null ? "" : text;
        int w = g.getFontMetrics().stringWidth(safe);
        g.drawString(safe, (int)Math.round(cx - w / 2.0), (int)Math.round(y));
    }

    private Map<String, NodeLayout> layout(GalaxyMapSnapshot snapshot, int width, int height) {
        Map<String, NodeLayout> out = new HashMap<>();
        List<GalaxyMapSystem> staticSystems = new ArrayList<>();
        List<GalaxyMapSystem> homes = new ArrayList<>();
        for (GalaxyMapSystem system : snapshot.systems()) {
            if (system.home()) homes.add(system); else staticSystems.add(system);
        }

        double centerX = width / 2.0;
        double centerY = height / 2.0 + 10;
        placeStaticRings(out, staticSystems, centerX, centerY, width, height);
        placeHomes(out, homes, centerX, centerY);
        return out;
    }

    private void placeStaticRings(Map<String, NodeLayout> out, List<GalaxyMapSystem> systems,
                                  double centerX, double centerY, int width, int height) {
        int count = systems.size();
        if (count == 0) return;
        int ringCapacity = count > 16 ? (int)Math.ceil(count / 2.0) : count;
        int rings = count > 16 ? 2 : 1;
        double maxRx = Math.max(150, (width - 190) * 0.43);
        double maxRy = Math.max(120, (height - 210) * 0.39);
        int index = 0;
        for (int ring = 0; ring < rings; ring++) {
            int remaining = count - index;
            int onRing = Math.min(ringCapacity, remaining);
            double scale = rings == 1 ? 1.0 : ring == 0 ? 1.0 : 0.66;
            double phase = ring == 0 ? -Math.PI / 2 : -Math.PI / 2 + Math.PI / Math.max(2, onRing);
            for (int i = 0; i < onRing; i++) {
                GalaxyMapSystem system = systems.get(index++);
                double angle = phase + i * Math.PI * 2.0 / onRing;
                out.put(system.id(), new NodeLayout(centerX + Math.cos(angle) * maxRx * scale,
                        centerY + Math.sin(angle) * maxRy * scale));
            }
        }
    }

    private void placeHomes(Map<String, NodeLayout> out, List<GalaxyMapSystem> homes, double centerX, double centerY) {
        int count = homes.size();
        for (int i = 0; i < count; i++) {
            double angle = -Math.PI / 2 + i * Math.PI * 2.0 / Math.max(1, count);
            double radius = count <= 1 ? 0 : 75 + count * 5;
            out.put(homes.get(i).id(), new NodeLayout(centerX + Math.cos(angle) * radius,
                    centerY + Math.sin(angle) * radius));
        }
    }

    private record NodeLayout(double x, double y) { }
}