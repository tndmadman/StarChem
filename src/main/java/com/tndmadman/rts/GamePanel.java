package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class GamePanel extends JPanel implements KeyListener, MouseListener, MouseMotionListener, MouseWheelListener, FocusListener {
    private static final double CAMERA_PAN_SPEED = 640.0;
    private static final int SELECT_DRAG_PX = 7;
    private static final long SOLO_FLEET_LOCATION_REFRESH_NANOS = 500_000_000L;
    private static final long CONTROL_GROUP_FOCUS_TIMEOUT_NANOS = 5_000_000_000L;
    private final World world;
    private final GameFrame owner;
    private final GameSettings settings;
    private final PeerNetwork network;
    private final PeerNetwork devAuthorityNetwork;
    private final GameServer server;
    private final GameClient client;
    private final Timer timer;
    private final BuildMenu buildMenu = new BuildMenu();
    private final ShipFittingWindow shipFittingWindow = new ShipFittingWindow();
    private final GameCamera camera = new GameCamera();
    private final MinimapHud minimapHud = new MinimapHud();
    private final EventHud eventHud = new EventHud();
    private final HangarHud hangarHud = new HangarHud();
    private final LeaderboardHud leaderboardHud = new LeaderboardHud();
    private final CombatPolicyHud combatPolicyHud = new CombatPolicyHud();
    private final ShieldDebugOverlay shieldDebugOverlay = new ShieldDebugOverlay();
    private final DevMenu devMenu = new DevMenu();
    private final AiDevPanel aiDevPanel = new AiDevPanel();
    private final AiDevOverlay aiDevOverlay = new AiDevOverlay();
    private final GalaxyMapOverlay galaxyMapOverlay = new GalaxyMapOverlay();
    private final PerfStats perfStats = new PerfStats();
    private final PerfOverlay perfOverlay = new PerfOverlay();
    private final ControlGroupManager controlGroups = new ControlGroupManager();
    private final LinkedHashSet<String> queuedPlanningUnits = new LinkedHashSet<>();
    private final boolean devMode;
    private FleetFormation formation = FleetFormation.GRID;
    private UnitOrderType commandMode = UnitOrderType.NONE;
    private Point2D patrolStart;
    private Point dragStart;
    private Point dragNow;
    private long lastNanos = System.nanoTime();
    private long lastControlGroupLocationRefreshNanos;
    private Map<String, String> controlGroupLocations = Map.of();
    private boolean controlGroupLocationsReady;
    private PendingControlGroupFocus pendingControlGroupFocus;
    private boolean cameraLeft, cameraRight, cameraUp, cameraDown;
    private boolean galaxyMapOpen;
    private boolean perfOverlayVisible;

    GamePanel(World world, GameFrame owner) { this(world, owner, null, false, null); }
    GamePanel(World world, GameFrame owner, PeerNetwork network) { this(world, owner, network, false, null); }
    GamePanel(World world, GameFrame owner, PeerNetwork network, boolean devMode) { this(world, owner, network, devMode, null); }

    GamePanel(World world, GameFrame owner, PeerNetwork network, boolean devMode, PeerNetwork devAuthorityNetwork) {
        this.world = world;
        this.owner = owner;
        this.settings = owner.gameSettings();
        this.network = network;
        this.devAuthorityNetwork = devAuthorityNetwork;
        this.server = GameServer.forNetwork(world, network);
        this.client = GameClient.forNetwork(world, network);
        this.devMode = devMode;
        setFocusable(true);
        setBackground(new Color(8, 12, 18));
        addKeyListener(this);
        addMouseListener(this);
        addMouseMotionListener(this);
        addMouseWheelListener(this);
        addFocusListener(this);
        timer = new Timer(16, e -> tick());
    }

    void start() {
        AiBrainLog.setEnabled(devMode && client == null);
        requestFocusInWindow();
        ProceduralAudio.prime();
        lastNanos = System.nanoTime();
        timer.start();
    }

    void stop() {
        timer.stop();
        AiBrainLog.setEnabled(false);
    }

    private void tick() {
        long now = System.nanoTime();
        double dt = Math.min(0.05, (now - lastNanos) / 1_000_000_000.0);
        lastNanos = now;
        long updateStarted = System.nanoTime();
        if (server != null) server.tick(dt);
        else if (client != null) client.tick(dt);
        else world.update(dt);
        perfStats.recordUpdate(System.nanoTime() - updateStarted);
        if (hasControlGroups() || pendingControlGroupFocus != null) {
            refreshControlGroupLocations(false, now);
            if (controlGroupLocationsReady) controlGroups.prune(controlGroupLocations);
            completePendingControlGroupFocus(now);
        }
        if (!galaxyMapOpen) updateCameraControls(dt);
        camera.update(world, getWidth(), getHeight(), dt);
        repaint();
    }

    private void updateCameraControls(double dt) {
        double dx = 0, dy = 0, step = CAMERA_PAN_SPEED * dt;
        if (cameraLeft) dx -= step;
        if (cameraRight) dx += step;
        if (cameraUp) dy -= step;
        if (cameraDown) dy += step;
        if (dx != 0 || dy != 0) camera.panByScreen(dx, dy, world, getWidth(), getHeight());
    }

    @Override protected void paintComponent(Graphics g) {
        long paintStarted = System.nanoTime();
        perfStats.frameStarted(paintStarted);
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        AffineTransform old = g2.getTransform();
        camera.apply(g2);
        world.draw(g2);
        if (devMode) aiDevOverlay.drawWorld(g2, world, aiDevPanel.overlayEnabled(), aiDevPanel.pathLinesEnabled());
        FogOfWarView.drawWorld(g2, world);
        drawSelectionBox(g2);
        g2.setTransform(old);
        WormholeIndicator.draw(g2, world, camera, getWidth(), getHeight());
        drawHud(g2);
        drawControlGroupHud(g2);
        combatPolicyHud.draw(g2, world);
        leaderboardHud.draw(g2, world, getWidth());
        hangarHud.draw(g2, world, getWidth());
        if (!galaxyMapOpen) {
            minimapHud.draw(g2, world, camera, getWidth(), getHeight());
            eventHud.draw(g2, world, getWidth());
        }
        if (world.devFreeBuild) shieldDebugOverlay.draw(g2, world, getWidth());
        if (devMode) {
            devMenu.draw(g2, world, canEditDev(), getHeight());
            aiDevPanel.draw(g2, world, devAuthorityNetwork, canEditDev(), getHeight());
        }
        buildMenu.draw(g2, getWidth(), getHeight());
        if (galaxyMapOpen) galaxyMapOverlay.draw(g2, world.galaxyMapSnapshot(), getWidth(), getHeight());
        if (devMode && perfOverlayVisible) {
            PerfSnapshot networkStats = network == null ? null : network.perfSnapshot();
            PerfSnapshot hostStats = devAuthorityNetwork == null ? null : devAuthorityNetwork.perfSnapshot();
            perfOverlay.draw(g2, world, getWidth(), updateLabel(), perfStats.snapshot(), networkStats, hostStats);
        }
        if (settings.isShowFps()) drawFpsCounter(g2);
        g2.dispose();
        perfStats.recordDraw(System.nanoTime() - paintStarted);
    }

    private void drawFpsCounter(Graphics2D g2) {
        String text = String.format(Locale.ROOT, "FPS %.1f", perfStats.snapshot().fps());
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 13f));
        int width = g2.getFontMetrics().stringWidth(text) + 20;
        int x = Math.max(12, getWidth() - width - 14);
        g2.setColor(new Color(0, 0, 0, 175));
        g2.fillRoundRect(x, 14, width, 28, 10, 10);
        g2.setColor(new Color(120, 205, 255));
        g2.drawRoundRect(x, 14, width, 28, 10, 10);
        g2.setColor(Color.WHITE);
        g2.drawString(text, x + 10, 33);
    }

    private String updateLabel() {
        if (network == null) return "Solo world update";
        if (devAuthorityNetwork != null || network.statusLine().startsWith("CLIENT")) return "Client prediction";
        return "World update";
    }

    private void drawHud(Graphics2D g2) {
        g2.setColor(new Color(0, 0, 0, 175));
        g2.fillRoundRect(12, 12, 1120, 132, 14, 14);
        g2.setColor(Color.WHITE);
        String dev = world.devFreeBuild ? " | FREE CRAFTING" : "";
        g2.drawString("StarChem | " + PlayerRegistry.name(PlayerRegistry.localId())
                + " | System: " + world.activeSystemId() + " | Selected: "
                + world.selectedCount() + dev, 28, 36);
        g2.setColor(new Color(210, 230, 245));
        g2.drawString(world.status, 28, 58);
        g2.drawString(network == null ? "Solo" : network.statusLine(), 28, 80);
        String minerRanges = UnitRenderer.miningRangeOverlayVisible() ? "ON" : "OFF";
        String audio = ProceduralAudio.muted() ? "OFF" : "ON";
        String perf = settings.hudDebugSuffix(devMode);
        g2.drawString("Galaxy: " + settings.bindingText("galaxy_map")
                + " | Inventory: " + settings.bindingText("inventory")
                + " | Narration: " + settings.bindingText("narration")
                + " | Formation: " + formation.label + " (" + settings.bindingText("formation") + ")"
                + " | Miner ranges: " + minerRanges + " (" + settings.bindingText("miner_range") + ")"
                + " | Audio: " + audio + " (" + settings.bindingText("mute_audio") + ")" + perf,
                28, 102);
        g2.drawString(settings.hudCommandLine() + " | Mode: " + commandModeLabel(), 28, 124);
        drawFittingButton(g2);
    }

    private void drawControlGroupHud(Graphics2D g2) {
        if (!hasControlGroups()) return;
        final int chipWidth = 106;
        final int chipHeight = 38;
        final int gap = 6;
        final int margin = 12;
        Rectangle minimap = minimapHud.bounds(world, getWidth(), getHeight());
        int availableRight = minimap.isEmpty() ? getWidth() - margin : Math.max(margin + chipWidth, minimap.x - gap);
        int maxColumns = Math.max(1, (availableRight - margin + gap) / (chipWidth + gap));
        int occupied = 0;
        for (int i = 0; i < ControlGroupManager.GROUP_COUNT; i++) {
            if (!controlGroups.empty(i)) occupied++;
        }
        int rows = Math.max(1, (occupied + maxColumns - 1) / maxColumns);
        int startY = Math.max(150, getHeight() - margin - rows * chipHeight - (rows - 1) * gap);
        int index = 0;
        int[] order = {1, 2, 3, 4, 5, 6, 7, 8, 9, 0};
        for (int groupNumber : order) {
            if (controlGroups.empty(groupNumber)) continue;
            int column = index % maxColumns;
            int row = index / maxColumns;
            int x = margin + column * (chipWidth + gap);
            int y = startY + row * (chipHeight + gap);
            index++;

            ControlGroupManager.GroupView view = controlGroups.view(groupNumber, world.activeSystemId(), controlGroupLocations);
            int living = controlGroupLocationsReady ? view.livingShips() : controlGroups.size(groupNumber);
            String systems = controlGroupLocationsReady ? Integer.toString(view.systemCount()) : "?";
            boolean active = controlGroups.activeGroup() == groupNumber;

            g2.setColor(new Color(0, 0, 0, active ? 210 : 175));
            g2.fillRoundRect(x, y, chipWidth, chipHeight, 10, 10);
            g2.setColor(active ? new Color(130, 225, 255) : new Color(70, 135, 175));
            g2.drawRoundRect(x, y, chipWidth, chipHeight, 10, 10);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
            g2.setColor(Color.WHITE);
            g2.drawString(Integer.toString(groupNumber), x + 8, y + 16);
            drawFormationGlyph(g2, view.formation(), x + 24, y + 7);
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 10f));
            g2.setColor(new Color(215, 232, 244));
            g2.drawString(living + " ships", x + 48, y + 15);
            g2.drawString(systems + " sys | " + view.formation().label, x + 48, y + 29);
        }
    }

    private void drawFormationGlyph(Graphics2D g2, FleetFormation formation, int x, int y) {
        Graphics2D icon = (Graphics2D) g2.create();
        icon.setColor(new Color(120, 205, 255));
        icon.setStroke(new BasicStroke(1.4f));
        switch (formation) {
            case GRID -> {
                icon.fillRect(x, y, 4, 4); icon.fillRect(x + 8, y, 4, 4);
                icon.fillRect(x, y + 8, 4, 4); icon.fillRect(x + 8, y + 8, 4, 4);
            }
            case LINE -> {
                icon.drawLine(x, y + 6, x + 14, y + 6);
                icon.fillOval(x - 1, y + 4, 4, 4); icon.fillOval(x + 6, y + 4, 4, 4); icon.fillOval(x + 13, y + 4, 4, 4);
            }
            case COLUMN -> {
                icon.drawLine(x + 7, y, x + 7, y + 14);
                icon.fillOval(x + 5, y - 1, 4, 4); icon.fillOval(x + 5, y + 5, 4, 4); icon.fillOval(x + 5, y + 12, 4, 4);
            }
            case WEDGE -> {
                icon.drawLine(x + 7, y + 2, x, y + 13);
                icon.drawLine(x + 7, y + 2, x + 14, y + 13);
                icon.fillOval(x + 5, y, 4, 4); icon.fillOval(x - 1, y + 11, 4, 4); icon.fillOval(x + 12, y + 11, 4, 4);
            }
        }
        icon.dispose();
    }

    private void drawFittingButton(Graphics2D g2) {
        Rectangle bounds = fittingButtonBounds();
        Unit unit = selectedFittingShip();
        boolean enabled = fittingAvailable(unit);
        g2.setColor(enabled ? new Color(18, 70, 104, 235) : new Color(35, 45, 54, 210));
        g2.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 10, 10);
        g2.setColor(enabled ? new Color(110, 215, 255) : new Color(105, 120, 130));
        g2.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 10, 10);
        g2.setColor(enabled ? Color.WHITE : new Color(145, 155, 162));
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 11f));
        String label = "FITTING [L]";
        int tx = bounds.x + (bounds.width - g2.getFontMetrics().stringWidth(label)) / 2;
        g2.drawString(label, tx, bounds.y + 19);
    }

    private Rectangle fittingButtonBounds() { return new Rectangle(958, 106, 156, 28); }

    private Unit selectedFittingShip() {
        Unit selected = null;
        for (Unit unit : world.selectedUnits()) {
            if (!PlayerRegistry.isLocal(unit.playerId)) continue;
            if (selected != null) return null;
            selected = unit;
        }
        return selected;
    }

    static boolean fittingAvailable(Unit unit) { return unit != null; }

    private void openSelectedFitting() {
        Unit unit = selectedFittingShip();
        if (unit == null) {
            world.status = world.selectedCount() > 1
                    ? "Select exactly one ship to open fitting."
                    : "Select a ship to open fitting.";
            ProceduralAudio.play(SoundCue.ERROR);
            repaint();
            return;
        }
        shipFittingWindow.showForUnit(this, world, network, unit);
        world.status = "Opened fitting for " + unit.type().name + " #" + unit.unitId + ".";
        ProceduralAudio.play(SoundCue.SELECT);
        repaint();
    }

    private void drawSelectionBox(Graphics2D g2) {
        if (dragStart == null || dragNow == null || !isSelectionDrag()) return;
        Rectangle2D box = screenRectToWorldRect(dragStart, dragNow);
        g2.setColor(new Color(80, 170, 255, 55));
        g2.fill(box);
        g2.setColor(new Color(120, 205, 255, 210));
        g2.draw(box);
    }

    private Point2D screenToWorld(Point p) { return camera.screenToWorld(p); }

    private Rectangle2D screenRectToWorldRect(Point a, Point b) {
        Point2D aw = screenToWorld(a), bw = screenToWorld(b);
        double x = Math.min(aw.getX(), bw.getX()), y = Math.min(aw.getY(), bw.getY());
        double w = Math.abs(aw.getX() - bw.getX()), h = Math.abs(aw.getY() - bw.getY());
        return new Rectangle2D.Double(x, y, w, h);
    }

    private Rectangle2D visibleWorldRect() {
        return screenRectToWorldRect(new Point(0, 0), new Point(getWidth(), getHeight()));
    }

    private boolean isSelectionDrag() {
        return dragStart != null && dragNow != null && dragStart.distance(dragNow) >= SELECT_DRAG_PX;
    }

    @Override public void mousePressed(MouseEvent e) {
        requestFocusInWindow();
        if (galaxyMapOpen) { clickGalaxyMap(e); return; }
        if (combatPolicyHud.click(e, world, this::applyCombatPolicy)) { repaint(); return; }
        if (buildMenu.click(e.getX(), e.getY())) return;
        if (SwingUtilities.isLeftMouseButton(e) && fittingButtonBounds().contains(e.getPoint())) {
            openSelectedFitting();
            return;
        }
        PeerNetwork devNetwork = devNetwork();
        if (devMode && aiDevPanel.click(world, devNetwork, e.getX(), e.getY(), canEditDev(), getHeight())) return;
        if (devMode && devMenu.click(world, devNetwork, e.getX(), e.getY(), canEditDev(), getHeight())) return;
        if (hangarHud.mousePressed(world, e.getX(), e.getY())) return;
        if (SwingUtilities.isLeftMouseButton(e)
                && minimapHud.click(world, camera, e.getX(), e.getY(), getWidth(), getHeight())) {
            clearCommandMode();
            dragStart = null;
            dragNow = null;
            ProceduralAudio.play(SoundCue.SELECT);
            repaint();
            return;
        }
        if (SwingUtilities.isRightMouseButton(e)) { clickRight(screenToWorld(e.getPoint()), e.isShiftDown()); return; }
        if (SwingUtilities.isLeftMouseButton(e)) { dragStart = e.getPoint(); dragNow = e.getPoint(); }
    }

    private void clickGalaxyMap(MouseEvent e) {
        if (!SwingUtilities.isLeftMouseButton(e)) return;
        GalaxyMapSnapshot snapshot = world.galaxyMapSnapshot();
        String systemId = galaxyMapOverlay.systemAt(snapshot, e.getX(), e.getY(), getWidth(), getHeight());
        if (systemId == null || systemId.isBlank()) return;
        if (network != null) {
            network.viewSystem(network.localPlayerId(), systemId);
            ProceduralAudio.play(SoundCue.SELECT);
            galaxyMapOpen = false;
            clearCameraKeys();
            repaint();
            return;
        }
        if (world.viewGalaxySystem(systemId)) {
            ProceduralAudio.play(SoundCue.SELECT);
            galaxyMapOpen = false;
            clearCameraKeys();
            repaint();
        }
    }

    private void clickLeft(MouseEvent e, Point2D p) {
        boolean visiblePoint = FogOfWarView.currentlyVisible(world, p.getX(), p.getY());
        boolean exploredPoint = FogOfWarView.explored(world, p.getX(), p.getY());
        String targetSystemId = exploredPoint ? world.wormholeTargetAt(p.getX(), p.getY()) : "";
        if (targetSystemId != null && !targetSystemId.isBlank() && network != null) {
            network.viewSystem(network.localPlayerId(), targetSystemId);
            ProceduralAudio.play(SoundCue.SELECT);
            return;
        }
        if (exploredPoint && world.jumpThroughWormholeAt(p.getX(), p.getY())) {
            ProceduralAudio.play(SoundCue.SELECT);
            return;
        }
        if (!visiblePoint) {
            clearSelection();
            world.status = "Unexplored space.";
            return;
        }
        Base base = world.baseAt(p.getX(), p.getY());
        Unit unit = world.unitAt(p.getX(), p.getY());
        if (base != null) {
            ProceduralAudio.play(SoundCue.SELECT);
            if (PlayerRegistry.isLocal(base.playerId)) {
                if (!StationControlMenu.showIfHandled(this, world, network, base, e.getX(), e.getY())) {
                    buildMenu.showForBase(world, network, base, e.getX(), e.getY());
                }
            } else world.status = "Enemy base: " + PlayerRegistry.name(base.playerId) + " | " + base.type().name + " | " + base.id;
            return;
        }
        if (unit != null && !PlayerRegistry.isLocal(unit.playerId)) {
            ProceduralAudio.play(SoundCue.SELECT);
            clearSelection();
            world.status = "Enemy ship: " + PlayerRegistry.name(unit.playerId) + " | " + unit.type().name + " | " + unit.task;
            return;
        }
        if (unit != null && e.getClickCount() >= 2) {
            ProceduralAudio.play(SoundCue.SELECT);
            selectVisibleShipsOfSameType(unit);
            return;
        }
        if (unit != null && !unit.basePackageType.isBlank()) {
            ProceduralAudio.play(SoundCue.SELECT);
            clickPackageCarrier(e, p, unit);
            return;
        }
        controlGroups.clearActive();
        world.selectAt(p.getX(), p.getY());
        if (world.status.startsWith("Selected ") || world.status.startsWith("Targeted ")) {
            ProceduralAudio.play(SoundCue.SELECT);
        }
    }

    private void selectVisibleShipsOfSameType(Unit clicked) {
        controlGroups.clearActive();
        Rectangle2D view = visibleWorldRect();
        int selected = 0;
        for (Unit unit : world.units.values()) {
            boolean match = PlayerRegistry.isLocal(unit.playerId)
                    && unit.shipTypeId.equals(clicked.shipTypeId) && view.contains(unit.x, unit.y);
            unit.selected = match;
            if (match) selected++;
        }
        world.selectedResourceId = -1;
        world.status = "Selected " + selected + " " + clicked.type().name + " ship(s) in view.";
    }

    private void clickPackageCarrier(MouseEvent e, Point2D p, Unit unit) {
        controlGroups.clearActive();
        world.selectAt(p.getX(), p.getY());
        buildMenu.showForUnit(world, network, unit, e.getX(), e.getY());
    }

    private void clickRight(Point2D p, boolean append) {
        if (commandMode != UnitOrderType.NONE) { handleCommandClick(p, append); return; }
        boolean visiblePoint = FogOfWarView.currentlyVisible(world, p.getX(), p.getY());
        boolean exploredPoint = FogOfWarView.explored(world, p.getX(), p.getY());
        Unit enemyUnit = visiblePoint ? world.unitAt(p.getX(), p.getY()) : null;
        if (enemyUnit != null && !PlayerRegistry.isLocal(enemyUnit.playerId)) {
            ProceduralAudio.play(SoundCue.ATTACK_ORDER);
            orderAttack(CombatTarget.unit(enemyUnit), append);
            return;
        }
        Base enemyBase = visiblePoint ? world.baseAt(p.getX(), p.getY()) : null;
        if (enemyBase != null && !PlayerRegistry.isLocal(enemyBase.playerId)) {
            ProceduralAudio.play(SoundCue.ATTACK_ORDER);
            orderAttack(CombatTarget.base(enemyBase), append);
            return;
        }
        WormholeGate gate = exploredPoint ? wormholeAt(p) : null;
        if (gate != null) {
            int applied = queueWormholeSelected(gate, append);
            world.status = applied > 0
                    ? (append ? "Queued" : "Ordered") + " wormhole transit for " + applied + " ship(s) to "
                    + StarSystems.get(gate.toSystemId).name() + "."
                    : "No ship available for that wormhole order.";
            ProceduralAudio.play(applied > 0 ? SoundCue.MOVE_ORDER : SoundCue.ERROR);
            return;
        }
        ResourceNode node = visiblePoint ? world.resourceAt(p.getX(), p.getY()) : null;
        if (node != null) {
            int applied = queueHarvestSelected(node, append);
            world.status = applied > 0
                    ? (append ? "Queued harvest of " : "Auto-harvesting ") + node.name + "."
                    : "Selected ship cannot harvest this node.";
            ProceduralAudio.play(applied > 0 ? SoundCue.HARVEST_ORDER : SoundCue.ERROR);
            return;
        }
        int applied = queueMoveSelected(p, append);
        world.status = applied > 0
                ? (append ? "Queued waypoint for " : "Moving ") + applied + " ship(s) in " + formation.label + " formation."
                : "No ship selected.";
        ProceduralAudio.play(applied > 0 ? SoundCue.MOVE_ORDER : SoundCue.ERROR);
    }

    private void handleCommandClick(Point2D p, boolean append) {
        if (commandMode == UnitOrderType.PATROL && patrolStart == null) {
            patrolStart = p;
            world.status = "Patrol start set. Right-click the second patrol point.";
            ProceduralAudio.play(SoundCue.SELECT);
            return;
        }
        if (commandMode == UnitOrderType.PATROL) {
            issueSelectedOrder(UnitOrderType.PATROL, patrolStart.getX(), patrolStart.getY(),
                    p.getX(), p.getY(), "", append);
            clearCommandMode();
            return;
        }
        if (commandMode == UnitOrderType.ATTACK_MOVE) {
            issueSelectedOrder(UnitOrderType.ATTACK_MOVE, p.getX(), p.getY(), p.getX(), p.getY(), "", append);
            clearCommandMode();
            return;
        }
        Unit unit = world.unitAt(p.getX(), p.getY());
        Base base = world.baseAt(p.getX(), p.getY());
        if (commandMode == UnitOrderType.ESCORT) {
            if (unit == null || !DiplomacySystem.allied(world, PlayerRegistry.localId(), unit.playerId)) {
                world.status = "Escort requires a friendly ship target.";
                ProceduralAudio.play(SoundCue.ERROR);
                return;
            }
            issueSelectedOrder(UnitOrderType.ESCORT, unit.x, unit.y, unit.x, unit.y,
                    CombatTarget.unit(unit), append);
            clearCommandMode();
            return;
        }
        if (commandMode == UnitOrderType.GUARD) {
            String target = "";
            if (unit != null && DiplomacySystem.allied(world, PlayerRegistry.localId(), unit.playerId)) target = CombatTarget.unit(unit);
            else if (base != null && DiplomacySystem.allied(world, PlayerRegistry.localId(), base.playerId)) target = CombatTarget.base(base);
            issueSelectedOrder(UnitOrderType.GUARD, p.getX(), p.getY(), p.getX(), p.getY(), target, append);
            clearCommandMode();
        }
    }

    private void issueSelectedOrder(UnitOrderType type, double x1, double y1,
                                    double x2, double y2, String targetKey, boolean append) {
        int applied = queueTacticalSelected(type, x1, y1, x2, y2, targetKey, append);
        world.status = applied > 0
                ? (append ? "Queued " : "Assigned ") + orderName(type) + " for " + applied + " ship(s)."
                : "Unable to assign " + orderName(type) + ".";
        ProceduralAudio.play(applied > 0 ? SoundCue.MOVE_ORDER : SoundCue.ERROR);
    }

    private void setCommandMode(UnitOrderType type) {
        if (world.selectedCount() <= 0 && queuedPlanningUnits.isEmpty()) {
            world.status = "Select one or more ships first.";
            ProceduralAudio.play(SoundCue.ERROR);
            return;
        }
        commandMode = type;
        patrolStart = null;
        world.status = switch (type) {
            case ATTACK_MOVE -> "Attack-move mode. Right-click a destination.";
            case PATROL -> "Patrol mode. Right-click the first patrol point.";
            case GUARD -> "Guard mode. Right-click a position, friendly ship, or friendly base.";
            case ESCORT -> "Escort mode. Right-click a friendly ship.";
            default -> "Command mode ready.";
        };
        ProceduralAudio.play(SoundCue.SELECT);
    }

    private void clearCommandMode() {
        commandMode = UnitOrderType.NONE;
        patrolStart = null;
    }

    private String commandModeLabel() {
        return switch (commandMode) {
            case ATTACK_MOVE -> "ATTACK-MOVE";
            case PATROL -> patrolStart == null ? "PATROL: FIRST POINT" : "PATROL: SECOND POINT";
            case GUARD -> "GUARD";
            case ESCORT -> "ESCORT";
            case HOLD -> "HOLD";
            case NONE -> "NORMAL";
        };
    }

    private WormholeGate wormholeAt(Point2D p) {
        for (WormholeGate gate : world.wormholes) if (gate.contains(p.getX(), p.getY())) return gate;
        return null;
    }

    private void orderAttack(String targetKey, boolean append) {
        int applied = queueAttackSelected(targetKey, append);
        world.status = applied > 0
                ? (append ? "Queued attack for " : "Attacking target with ") + applied + " ship(s)."
                : "No valid attack-capable ship selected.";
        if (applied <= 0) ProceduralAudio.play(SoundCue.ERROR);
    }

    private int queueMoveSelected(Point2D point, boolean append) {
        List<String> keys = commandUnitKeys(append);
        if (keys.isEmpty()) return 0;
        int applied = 0;
        for (int i = 0; i < keys.size(); i++) {
            Point2D target = queuedFormationTarget(point.getX(), point.getY(), i, keys.size());
            QueuedUnitCommand command = QueuedUnitCommand.move(world.activeSystemId(), target.getX(), target.getY());
            if (issueQueueMutation(keys.get(i), command, append ? UnitQueueOperation.APPEND : UnitQueueOperation.REPLACE)) applied++;
        }
        return applied;
    }

    private int queueAttackSelected(String targetKey, boolean append) {
        List<String> keys = commandUnitKeys(append);
        if (keys.isEmpty()) return 0;
        int applied = 0;
        for (String key : keys) {
            Unit present = world.units.get(key);
            if (present != null && !WeaponRules.armed(world, present)) continue;
            if (issueQueueMutation(key, QueuedUnitCommand.attack(world.activeSystemId(), targetKey),
                    append ? UnitQueueOperation.APPEND : UnitQueueOperation.REPLACE)) applied++;
        }
        return applied;
    }

    private int queueHarvestSelected(ResourceNode node, boolean append) {
        if (node == null) return 0;
        List<String> keys = commandUnitKeys(append);
        if (keys.isEmpty()) return 0;
        int applied = 0;
        for (String key : keys) {
            Unit present = world.units.get(key);
            if (present != null && !present.type().harvestKinds.contains(node.kind)) continue;
            if (issueQueueMutation(key, QueuedUnitCommand.harvest(world.activeSystemId(), node.id),
                    append ? UnitQueueOperation.APPEND : UnitQueueOperation.REPLACE)) applied++;
        }
        return applied;
    }

    private int queueWormholeSelected(WormholeGate gate, boolean append) {
        if (gate == null) return 0;
        List<String> keys = commandUnitKeys(append);
        if (keys.isEmpty()) return 0;
        int applied = 0;
        for (String key : keys) {
            if (issueQueueMutation(key, QueuedUnitCommand.wormhole(world.activeSystemId(), gate.id, gate.toSystemId),
                    append ? UnitQueueOperation.APPEND : UnitQueueOperation.REPLACE)) applied++;
        }
        return applied;
    }

    private int queueTacticalSelected(UnitOrderType type, double x1, double y1,
                                      double x2, double y2, String targetKey, boolean append) {
        List<String> keys = commandUnitKeys(append);
        if (keys.isEmpty()) return 0;
        int applied = 0;
        for (int i = 0; i < keys.size(); i++) {
            double ax = x1, ay = y1, bx = x2, by = y2;
            if (type == UnitOrderType.ATTACK_MOVE) {
                Point2D end = queuedFormationTarget(x2, y2, i, keys.size());
                ax = bx = end.getX(); ay = by = end.getY();
            } else if (type == UnitOrderType.PATROL) {
                Point2D start = queuedFormationTarget(x1, y1, i, keys.size());
                Point2D end = queuedFormationTarget(x2, y2, i, keys.size());
                ax = start.getX(); ay = start.getY(); bx = end.getX(); by = end.getY();
            } else if (type == UnitOrderType.GUARD && (targetKey == null || targetKey.isBlank())) {
                Point2D anchor = queuedFormationTarget(x1, y1, i, keys.size());
                ax = bx = anchor.getX(); ay = by = anchor.getY();
            }
            double radius = UnitOrderSystem.defaultRadius(type);
            QueuedUnitCommand command = QueuedUnitCommand.tactical(world.activeSystemId(), type,
                    ax, ay, bx, by, radius, targetKey);
            if (issueQueueMutation(keys.get(i), command,
                    append ? UnitQueueOperation.APPEND : UnitQueueOperation.REPLACE)) applied++;
        }
        return applied;
    }

    private int applyCombatPolicy(CombatStance stance, TargetPriorityPolicy priority) {
        List<String> keys = commandUnitKeys(false);
        if (keys.isEmpty()) return 0;
        int applied = 0;
        for (String key : keys) {
            QueuedUnitCommand command = QueuedUnitCommand.policy(world.activeSystemId(), stance, priority);
            if (issueQueueMutation(key, command, UnitQueueOperation.POLICY)) applied++;
        }
        return applied;
    }

    private boolean issueQueueMutation(String key, QueuedUnitCommand command, UnitQueueOperation operation) {
        int unitId = unitIdFromKey(key);
        String playerId = PlayerRegistry.localId();
        if (unitId < 0 || playerId == null || playerId.isBlank()) return false;
        UnitQueueMutation mutation = new UnitQueueMutation(playerId, unitId, operation,
                UnitCommandQueueSystem.revision(world, key), command);
        if (network == null) return UnitCommandQueueSystem.applyGlobal(world, mutation) == UnitQueueApplyResult.APPLIED;
        if (network.clientMode()) {
            if (!network.clientReady()) return false;
            if (UnitCommandQueueSystem.predict(world, mutation) != UnitQueueApplyResult.APPLIED) return false;
            network.queue(mutation);
            return true;
        }
        return network.queue(mutation) == UnitQueueApplyResult.APPLIED;
    }

    private List<String> commandUnitKeys(boolean append) {
        List<String> selected = new ArrayList<>();
        for (Unit unit : world.selectedUnits()) {
            if (PlayerRegistry.isLocal(unit.playerId)) selected.add(unit.key());
        }
        if (!selected.isEmpty()) {
            queuedPlanningUnits.clear();
            queuedPlanningUnits.addAll(selected);
            return selected;
        }
        if (append && !queuedPlanningUnits.isEmpty()) return new ArrayList<>(queuedPlanningUnits);
        return List.of();
    }

    private void clearQueuedOrders() {
        List<String> keys = commandUnitKeys(true);
        int cleared = 0;
        for (String key : keys) {
            int unitId = unitIdFromKey(key);
            if (unitId < 0) continue;
            UnitQueueMutation mutation = new UnitQueueMutation(PlayerRegistry.localId(), unitId,
                    UnitQueueOperation.CLEAR, UnitCommandQueueSystem.revision(world, key), null);
            boolean success;
            if (network == null) success = UnitCommandQueueSystem.applyGlobal(world, mutation) == UnitQueueApplyResult.APPLIED;
            else if (network.clientMode()) {
                success = network.clientReady() && UnitCommandQueueSystem.predict(world, mutation) == UnitQueueApplyResult.APPLIED;
                if (success) network.queue(mutation);
            } else success = network.queue(mutation) == UnitQueueApplyResult.APPLIED;
            if (success) cleared++;
        }
        queuedPlanningUnits.clear();
        world.status = cleared > 0 ? "Stopped and cleared orders for " + cleared + " ship(s)." : "No queued ships to stop.";
        ProceduralAudio.play(cleared > 0 ? SoundCue.SELECT : SoundCue.ERROR);
    }

    private Point2D queuedFormationTarget(double x, double y, int index, int count) {
        double spacing = 54, ox = 0, oy = 0;
        switch (formation) {
            case LINE -> ox = (index - (count - 1) / 2.0) * spacing;
            case COLUMN -> oy = (index - (count - 1) / 2.0) * spacing;
            case WEDGE -> {
                if (index > 0) {
                    int rank = (index + 1) / 2;
                    int side = index % 2 == 1 ? -1 : 1;
                    ox = side * rank * spacing;
                    oy = rank * spacing;
                }
            }
            case GRID -> {
                int cols = (int)Math.ceil(Math.sqrt(count));
                double rows = Math.ceil(count / (double)cols);
                int col = index % cols;
                int row = index / cols;
                ox = (col - (cols - 1) / 2.0) * 42;
                oy = (row - (rows - 1) / 2.0) * 42;
            }
        }
        return new Point2D.Double(Calc.clamp(x + ox, 0, world.width), Calc.clamp(y + oy, 0, world.height));
    }

    private int unitIdFromKey(String key) {
        if (key == null) return -1;
        int separator = key.lastIndexOf(':');
        if (separator < 0 || separator + 1 >= key.length()) return -1;
        try { return Integer.parseInt(key.substring(separator + 1)); }
        catch (RuntimeException ignored) { return -1; }
    }

    private String orderName(UnitOrderType type) {
        return switch (type) {
            case PATROL -> "patrol";
            case GUARD -> "guard";
            case ESCORT -> "escort";
            case HOLD -> "hold position";
            case ATTACK_MOVE -> "attack-move";
            case NONE -> "order";
        };
    }

    private void clearSelection() {
        controlGroups.clearActive();
        for (Unit unit : world.units.values()) unit.selected = false;
    }

    private PeerNetwork devNetwork() { return devAuthorityNetwork != null ? devAuthorityNetwork : network; }

    private boolean canEditDev() {
        if (!devMode) return false;
        if (network == null) return true;
        PeerNetwork devNetwork = devNetwork();
        return devNetwork != null && devNetwork.devToolsAllowed();
    }

    @Override public void mouseDragged(MouseEvent e) {
        if (galaxyMapOpen) return;
        if (dragStart != null) { dragNow = e.getPoint(); repaint(); return; }
        hangarHud.mouseDragged(e.getX(), e.getY(), getWidth(), getHeight());
        if (devMode) {
            aiDevPanel.drag(e.getX(), e.getY(), getWidth(), getHeight());
            devMenu.drag(e.getX(), e.getY(), getWidth(), getHeight());
        }
    }

    @Override public void mouseReleased(MouseEvent e) {
        if (galaxyMapOpen) { dragStart = null; dragNow = null; return; }
        if (SwingUtilities.isLeftMouseButton(e) && dragStart != null) {
            dragNow = e.getPoint();
            if (isSelectionDrag()) {
                controlGroups.clearActive();
                world.selectBox(screenRectToWorldRect(dragStart, dragNow));
                if (world.selectedCount() > 0) ProceduralAudio.play(SoundCue.SELECT);
            } else {
                clickLeft(e, screenToWorld(e.getPoint()));
            }
            dragStart = null;
            dragNow = null;
            repaint();
        }
        hangarHud.mouseReleased();
        if (devMode) { aiDevPanel.release(); devMenu.release(); }
    }

    @Override public void mouseWheelMoved(MouseWheelEvent e) {
        if (buildMenu.scroll(e.getX(), e.getY(), e.getPreciseWheelRotation(), getWidth(), getHeight())) {
            repaint();
            return;
        }
        if (devMode && aiDevPanel.scroll(e.getX(), e.getY(), e.getWheelRotation(), getHeight())) {
            repaint();
            return;
        }
        if (devMode && devMenu.scroll(e.getX(), e.getY(), e.getWheelRotation(), getHeight())) {
            repaint();
            return;
        }
        if (!galaxyMapOpen && !minimapHud.bounds(world, getWidth(), getHeight()).contains(e.getPoint())) {
            camera.zoomAt(e.getPoint(), e.getWheelRotation(), world, getWidth(), getHeight());
        }
    }

    @Override public void mouseMoved(MouseEvent e) { }
    @Override public void mouseClicked(MouseEvent e) { }
    @Override public void mouseEntered(MouseEvent e) { }
    @Override public void mouseExited(MouseEvent e) { }
    @Override public void keyTyped(KeyEvent e) { }

    @Override public void keyPressed(KeyEvent e) {
        if (handleControlGroupKey(e)) return;
        if (e.getKeyCode() == KeyEvent.VK_L && !e.isControlDown() && !e.isAltDown() && !e.isMetaDown()) {
            openSelectedFitting();
            return;
        }
        if (settings.matches("mute_audio", e)) {
            boolean muted = ProceduralAudio.toggleMute();
            world.status = muted ? "Audio muted." : "Audio enabled.";
            repaint();
            return;
        }
        if (settings.matches("galaxy_map", e)) { toggleGalaxyMap(); return; }
        if (matchesReboundOnly("inventory", e, KeyEvent.VK_I, false)) {
            owner.toggleResourceCatalogFromGame();
            return;
        }
        if (matchesReboundOnly("narration", e, KeyEvent.VK_F8, false)) {
            owner.toggleNarrationFromGame();
            return;
        }
        if (devMode && settings.matches("ai_debug_overlay", e)) {
            boolean visible = aiDevPanel.toggleOverlay();
            world.status = "AI debug overlay: " + (visible ? "ON" : "OFF") + ".";
            repaint();
            return;
        }
        if (devMode && settings.matches("performance_overlay", e)) {
            perfOverlayVisible = !perfOverlayVisible;
            world.status = "Performance overlay: " + (perfOverlayVisible ? "ON" : "OFF") + ".";
            repaint();
            return;
        }
        if (settings.matches("formation", e)) {
            formation = formation.next();
            refreshControlGroupLocations(false, System.nanoTime());
            controlGroups.rememberFormationIfSelectionMatches(
                    selectedLocalUnitKeys(), world.activeSystemId(), controlGroupLocations, formation);
            world.status = "Fleet formation: " + formation.label + ".";
            ProceduralAudio.play(SoundCue.SELECT);
            return;
        }
        if (settings.matches("miner_range", e)) {
            UnitRenderer.toggleMiningRangeOverlay();
            world.status = "Miner range overlay: "
                    + (UnitRenderer.miningRangeOverlayVisible() ? "ON" : "OFF") + ".";
            ProceduralAudio.play(SoundCue.SELECT);
            repaint();
            return;
        }
        if (settings.matches("stop_orders", e)) { clearQueuedOrders(); clearCommandMode(); return; }
        if (settings.matches("attack_move", e)) { setCommandMode(UnitOrderType.ATTACK_MOVE); return; }
        if (settings.matches("patrol", e)) { setCommandMode(UnitOrderType.PATROL); return; }
        if (settings.matches("guard", e)) { setCommandMode(UnitOrderType.GUARD); return; }
        if (settings.matches("escort", e)) { setCommandMode(UnitOrderType.ESCORT); return; }
        if (settings.matches("hold", e)) {
            issueSelectedOrder(UnitOrderType.HOLD, 0, 0, 0, 0, "", false);
            clearCommandMode();
            return;
        }
        setCameraKey(e, true);
    }

    private boolean handleControlGroupKey(KeyEvent event) {
        int groupNumber = ControlGroupManager.numberForKeyCode(event.getKeyCode());
        if (groupNumber < 0) return false;
        if (!controlGroups.acceptKeyPress(event.getKeyCode())) return true;
        if (controlGroupInputBlocked()) return true;

        boolean ctrl = event.isControlDown();
        boolean shift = event.isShiftDown();
        boolean alt = event.isAltDown();
        boolean meta = event.isMetaDown();
        int modifierCount = (ctrl ? 1 : 0) + (shift ? 1 : 0) + (alt ? 1 : 0) + (meta ? 1 : 0);
        if (meta || modifierCount > 1) return true;

        long now = System.nanoTime();
        refreshControlGroupLocations(true, now);
        if (controlGroupLocationsReady) controlGroups.prune(controlGroupLocations);
        Set<String> selectedKeys = selectedLocalUnitKeys();

        if (ctrl) {
            if (selectedKeys.isEmpty()) {
                controlGroups.clear(groupNumber);
                world.status = "Control group " + groupNumber + " cleared.";
            } else {
                controlGroups.assign(groupNumber, selectedKeys, formation);
                controlGroups.markActive(groupNumber, world.activeSystemId());
                world.status = "Control group " + groupNumber + " assigned: " + selectedKeys.size() + " ship(s).";
            }
            ProceduralAudio.play(SoundCue.SELECT);
            repaint();
            return true;
        }
        if (shift) {
            if (selectedKeys.isEmpty()) {
                world.status = "Select ships to add to control group " + groupNumber + ".";
                ProceduralAudio.play(SoundCue.ERROR);
            } else {
                controlGroups.add(groupNumber, selectedKeys);
                controlGroups.markActive(groupNumber, world.activeSystemId());
                world.status = "Added " + selectedKeys.size() + " ship(s) to control group " + groupNumber + ".";
                ProceduralAudio.play(SoundCue.SELECT);
            }
            repaint();
            return true;
        }
        if (alt) {
            if (selectedKeys.isEmpty()) {
                world.status = "Select ships to remove from control group " + groupNumber + ".";
                ProceduralAudio.play(SoundCue.ERROR);
            } else {
                controlGroups.remove(groupNumber, selectedKeys);
                world.status = "Removed selected ships from control group " + groupNumber + ".";
                ProceduralAudio.play(SoundCue.SELECT);
            }
            repaint();
            return true;
        }

        boolean doubleTap = controlGroups.registerTap(groupNumber, now);
        recallControlGroup(groupNumber, doubleTap, now);
        return true;
    }

    private boolean controlGroupInputBlocked() {
        Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        return ControlGroupInputGate.blocked(galaxyMapOpen, ShipFittingWindow.active(), focusOwner, this);
    }

    private void recallControlGroup(int groupNumber, boolean focusCamera, long now) {
        if (controlGroupLocationsReady) controlGroups.prune(controlGroupLocations);
        if (controlGroups.empty(groupNumber)) {
            clearSelectedFlagsOnly();
            world.status = "Control group " + groupNumber + " is empty.";
            ProceduralAudio.play(SoundCue.ERROR);
            repaint();
            return;
        }

        formation = controlGroups.formation(groupNumber);
        int selectedHere = selectControlGroupInCurrentSystem(groupNumber);
        controlGroups.markActive(groupNumber, world.activeSystemId());

        if (!focusCamera) {
            if (controlGroupLocationsReady) {
                ControlGroupManager.GroupView view = controlGroups.view(groupNumber, world.activeSystemId(), controlGroupLocations);
                world.status = "Control group " + groupNumber + ": selected " + selectedHere + " here; "
                        + view.livingShips() + " ship(s) across " + view.systemCount() + " system(s).";
            } else {
                world.status = "Control group " + groupNumber + ": selected " + selectedHere
                        + " here; remote locations are synchronizing.";
            }
            ProceduralAudio.play(SoundCue.SELECT);
            repaint();
            return;
        }

        if (!controlGroupLocationsReady) {
            if (selectedHere > 0) {
                centerOnControlGroup(groupNumber);
                world.status = "Centered camera on control group " + groupNumber + ".";
                ProceduralAudio.play(SoundCue.SELECT);
            } else {
                world.status = "Control group locations are still synchronizing.";
                ProceduralAudio.play(SoundCue.ERROR);
            }
            repaint();
            return;
        }

        String targetSystemId = controlGroups.focusSystem(groupNumber, world.activeSystemId(), controlGroupLocations);
        if (targetSystemId.isBlank()) {
            controlGroups.clear(groupNumber);
            clearSelectedFlagsOnly();
            world.status = "Control group " + groupNumber + " no longer contains living ships.";
            ProceduralAudio.play(SoundCue.ERROR);
            repaint();
            return;
        }
        focusControlGroupSystem(groupNumber, targetSystemId, now);
    }

    private void focusControlGroupSystem(int groupNumber, String targetSystemId, long now) {
        if (targetSystemId.equals(world.activeSystemId())) {
            int selected = selectControlGroupInCurrentSystem(groupNumber);
            controlGroups.markActive(groupNumber, targetSystemId);
            if (selected > 0) centerOnControlGroup(groupNumber);
            world.status = selected > 0
                    ? "Centered camera on control group " + groupNumber + "."
                    : "Control group " + groupNumber + " has no ships in this system.";
            ProceduralAudio.play(selected > 0 ? SoundCue.SELECT : SoundCue.ERROR);
            repaint();
            return;
        }

        galaxyMapOpen = false;
        clearCameraKeys();
        clearCommandMode();
        if (network != null) {
            network.viewSystem(network.localPlayerId(), targetSystemId);
            pendingControlGroupFocus = new PendingControlGroupFocus(groupNumber, targetSystemId, now);
            world.status = "Control group " + groupNumber + ": switching to " + targetSystemId + ".";
            ProceduralAudio.play(SoundCue.SELECT);
            repaint();
            return;
        }
        if (world.viewGalaxySystem(targetSystemId)) {
            refreshControlGroupLocations(true, now);
            int selected = selectControlGroupInCurrentSystem(groupNumber);
            controlGroups.markActive(groupNumber, targetSystemId);
            if (selected > 0) centerOnControlGroup(groupNumber);
            world.status = selected > 0
                    ? "Centered camera on control group " + groupNumber + " in " + targetSystemId + "."
                    : "Control group " + groupNumber + " moved before camera recall completed.";
            ProceduralAudio.play(selected > 0 ? SoundCue.SELECT : SoundCue.ERROR);
        } else {
            world.status = "Unable to view system for control group " + groupNumber + ".";
            ProceduralAudio.play(SoundCue.ERROR);
        }
        repaint();
    }

    private void completePendingControlGroupFocus(long now) {
        PendingControlGroupFocus pending = pendingControlGroupFocus;
        if (pending == null) return;
        if (now - pending.requestedAtNanos() > CONTROL_GROUP_FOCUS_TIMEOUT_NANOS) {
            pendingControlGroupFocus = null;
            world.status = "Control group camera recall timed out.";
            return;
        }
        if (!pending.systemId().equals(world.activeSystemId())) return;

        refreshControlGroupLocations(true, now);
        if (controlGroupLocationsReady) controlGroups.prune(controlGroupLocations);
        if (controlGroups.empty(pending.groupNumber())) {
            pendingControlGroupFocus = null;
            clearSelectedFlagsOnly();
            world.status = "Control group " + pending.groupNumber() + " no longer contains living ships.";
            return;
        }

        formation = controlGroups.formation(pending.groupNumber());
        int selected = selectControlGroupInCurrentSystem(pending.groupNumber());
        if (selected > 0) {
            pendingControlGroupFocus = null;
            controlGroups.markActive(pending.groupNumber(), pending.systemId());
            centerOnControlGroup(pending.groupNumber());
            world.status = "Centered camera on control group " + pending.groupNumber() + " in " + pending.systemId() + ".";
            ProceduralAudio.play(SoundCue.SELECT);
            return;
        }

        if (controlGroupLocationsReady) {
            String nextSystem = controlGroups.focusSystem(pending.groupNumber(), world.activeSystemId(), controlGroupLocations);
            if (!nextSystem.isBlank() && !nextSystem.equals(world.activeSystemId()) && network != null) {
                network.viewSystem(network.localPlayerId(), nextSystem);
                pendingControlGroupFocus = new PendingControlGroupFocus(pending.groupNumber(), nextSystem, now);
                world.status = "Control group moved; following it to " + nextSystem + ".";
                return;
            }
            pendingControlGroupFocus = null;
            world.status = "Control group " + pending.groupNumber() + " moved before camera recall completed.";
        }
    }

    private int selectControlGroupInCurrentSystem(int groupNumber) {
        int selected = 0;
        for (Unit unit : world.units.values()) {
            boolean match = unit != null && unit.hp > 0 && PlayerRegistry.isLocal(unit.playerId)
                    && controlGroups.contains(groupNumber, unit.key());
            unit.selected = match;
            if (match) selected++;
        }
        world.selectedResourceId = -1;
        return selected;
    }

    private void centerOnControlGroup(int groupNumber) {
        boolean found = false;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Unit unit : world.units.values()) {
            if (unit == null || unit.hp <= 0 || !PlayerRegistry.isLocal(unit.playerId)
                    || !controlGroups.contains(groupNumber, unit.key())) continue;
            found = true;
            minX = Math.min(minX, unit.x);
            minY = Math.min(minY, unit.y);
            maxX = Math.max(maxX, unit.x);
            maxY = Math.max(maxY, unit.y);
        }
        if (found) camera.centerAt((minX + maxX) * 0.5, (minY + maxY) * 0.5,
                world, getWidth(), getHeight());
    }

    private Set<String> selectedLocalUnitKeys() {
        Set<String> out = new LinkedHashSet<>();
        for (Unit unit : world.selectedUnits()) {
            if (unit != null && unit.hp > 0 && PlayerRegistry.isLocal(unit.playerId)) out.add(unit.key());
        }
        return out;
    }

    private void clearSelectedFlagsOnly() {
        for (Unit unit : world.units.values()) unit.selected = false;
        world.selectedResourceId = -1;
    }

    private boolean hasControlGroups() {
        for (int i = 0; i < ControlGroupManager.GROUP_COUNT; i++) if (!controlGroups.empty(i)) return true;
        return false;
    }

    private void refreshControlGroupLocations(boolean force, long nowNanos) {
        Map<String, String> base = controlGroupLocations;
        if (network == null) {
            if (force || !controlGroupLocationsReady
                    || nowNanos - lastControlGroupLocationRefreshNanos >= SOLO_FLEET_LOCATION_REFRESH_NANOS) {
                base = OwnerFleetLocations.capture(world, PlayerRegistry.localId());
                controlGroupLocationsReady = true;
                lastControlGroupLocationRefreshNanos = nowNanos;
            }
        } else {
            OwnerFleetLocationRegistry.State state = OwnerFleetLocationRegistry.state(world);
            String localPlayerId = network.localPlayerId();
            if (state.initialized() && state.ownerId().equals(localPlayerId)) {
                base = state.locations();
                controlGroupLocationsReady = true;
            } else {
                base = Map.of();
                controlGroupLocationsReady = false;
            }
        }

        Map<String, String> merged = new LinkedHashMap<>(base == null ? Map.of() : base);
        String activeSystemId = world.activeSystemId();
        for (Unit unit : world.units.values()) {
            if (unit != null && unit.hp > 0 && PlayerRegistry.isLocal(unit.playerId)) {
                merged.put(unit.key(), activeSystemId);
            }
        }
        controlGroupLocations = Map.copyOf(merged);
    }

    private boolean matchesReboundOnly(String actionId, KeyEvent event,
                                       int defaultKey, boolean defaultControl) {
        GameSettings.Binding binding = settings.binding(actionId);
        if (binding == null || !settings.matches(actionId, event)) return false;
        return binding.keyCode() != defaultKey || binding.ctrlRequired() != defaultControl;
    }

    @Override public void keyReleased(KeyEvent e) {
        controlGroups.releaseKey(e.getKeyCode());
        setCameraKey(e, false);
    }
    @Override public void focusGained(FocusEvent e) { }

    @Override public void focusLost(FocusEvent e) {
        clearCameraKeys();
        clearCommandMode();
        controlGroups.clearHeldKeys();
        dragStart = null;
        dragNow = null;
    }

    boolean handleEscapeBeforeMenu() {
        if (commandMode != UnitOrderType.NONE) {
            clearCommandMode();
            world.status = "Command mode cancelled.";
            repaint();
            return true;
        }
        if (galaxyMapOpen) {
            closeGalaxyMap();
            return true;
        }
        return false;
    }

    private void toggleGalaxyMap() {
        galaxyMapOpen = !galaxyMapOpen;
        clearCommandMode();
        dragStart = null;
        dragNow = null;
        clearCameraKeys();
        world.status = galaxyMapOpen
                ? "Galaxy map open. Click a system to travel/view it." : "Galaxy map closed.";
        ProceduralAudio.play(SoundCue.SELECT);
        repaint();
    }

    private void closeGalaxyMap() {
        galaxyMapOpen = false;
        clearCommandMode();
        dragStart = null;
        dragNow = null;
        clearCameraKeys();
        world.status = "Galaxy map closed.";
        repaint();
    }

    private void clearCameraKeys() {
        cameraLeft = cameraRight = cameraUp = cameraDown = false;
    }

    private void setCameraKey(KeyEvent event, boolean down) {
        if (settings.matches("camera_left_wasd", event)
                || settings.matches("camera_left_arrow", event)) cameraLeft = down;
        if (settings.matches("camera_right_wasd", event)
                || settings.matches("camera_right_arrow", event)) cameraRight = down;
        if (settings.matches("camera_up_wasd", event)
                || settings.matches("camera_up_arrow", event)) cameraUp = down;
        if (settings.matches("camera_down_wasd", event)
                || settings.matches("camera_down_arrow", event)) cameraDown = down;
    }

    private record PendingControlGroupFocus(int groupNumber, String systemId, long requestedAtNanos) { }
}
