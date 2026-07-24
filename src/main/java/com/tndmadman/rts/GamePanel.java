package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.List;

final class GamePanel extends JPanel implements KeyListener, MouseListener, MouseMotionListener, MouseWheelListener, FocusListener {
    private static final double CAMERA_PAN_SPEED = 640.0;
    private static final int SELECT_DRAG_PX = 7;
    private final World world;
    private final GameFrame owner;
    private final PeerNetwork network;
    private final PeerNetwork devAuthorityNetwork;
    private final GameServer server;
    private final GameClient client;
    private final Timer timer;
    private final GameMenuOverlay gameMenu = new GameMenuOverlay();
    private boolean gameMenuVisible = false;
    private final BuildMenu buildMenu = new BuildMenu();
    private final GameCamera camera = new GameCamera();
    private final MinimapHud minimapHud = new MinimapHud();
    private final HangarHud hangarHud = new HangarHud();
    private final LeaderboardHud leaderboardHud = new LeaderboardHud();
    private final ShieldDebugOverlay shieldDebugOverlay = new ShieldDebugOverlay();
    private final DevMenu devMenu = new DevMenu();
    private final AiDevPanel aiDevPanel = new AiDevPanel();
    private final AiDevOverlay aiDevOverlay = new AiDevOverlay();
    private final GalaxyMapOverlay galaxyMapOverlay = new GalaxyMapOverlay();
    private final PerfStats perfStats = new PerfStats();
    private final PerfOverlay perfOverlay = new PerfOverlay();
    private final boolean devMode;
    private FleetFormation formation = FleetFormation.GRID;
    private UnitOrderType commandMode = UnitOrderType.NONE;
    private Point2D patrolStart;
    private Point dragStart;
    private Point dragNow;
    private long lastPaintNanos = System.nanoTime();
    private int currentFps = 0;
    private long lastNanos = System.nanoTime();
    private boolean cameraLeft, cameraRight, cameraUp, cameraDown;
    private boolean galaxyMapOpen;
    private boolean perfOverlayVisible;

    GamePanel(World world, GameFrame owner) { this(world, owner, null, false, null); }
    GamePanel(World world, GameFrame owner, PeerNetwork network) { this(world, owner, network, false, null); }
    GamePanel(World world, GameFrame owner, PeerNetwork network, boolean devMode) { this(world, owner, network, devMode, null); }

    GamePanel(World world, GameFrame owner, PeerNetwork network, boolean devMode, PeerNetwork devAuthorityNetwork) {
        this.world = world;
        this.owner = owner;
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
        timer.start();
    }

    void stop() {
        timer.stop();
        AiBrainLog.setEnabled(false);
    }
    
    private void setCameraKeyFromBindings(KeyEvent e, boolean down) {
    if (gameMenu.matchesAction("camera_left_wasd", e) || gameMenu.matchesAction("camera_left_arrow", e)) cameraLeft = down;
    if (gameMenu.matchesAction("camera_right_wasd", e) || gameMenu.matchesAction("camera_right_arrow", e)) cameraRight = down;
    if (gameMenu.matchesAction("camera_up_wasd", e) || gameMenu.matchesAction("camera_up_arrow", e)) cameraUp = down;
    if (gameMenu.matchesAction("camera_down_wasd", e) || gameMenu.matchesAction("camera_down_arrow", e)) cameraDown = down;
}

private void drawFpsOverlay(Graphics2D g2) {
    if (!gameMenu.isShowFpsEnabled()) {
        return;
    }

    String text = "FPS: " + currentFps;
    Font font = new Font("SansSerif", Font.BOLD, 14);
    g2.setFont(font);
    FontMetrics fm = g2.getFontMetrics();

    int padX = 10;
    int padY = 6;
    int w = fm.stringWidth(text) + padX * 2;
    int h = fm.getHeight() + padY * 2;
    int x = getWidth() - w - 16;
    int y = 16;

    g2.setColor(new Color(0, 0, 0, 165));
    g2.fillRoundRect(x, y, w, h, 10, 10);

    g2.setColor(new Color(90, 170, 240));
    g2.setStroke(new BasicStroke(1.5f));
    g2.drawRoundRect(x, y, w, h, 10, 10);

    g2.setColor(Color.WHITE);
    g2.drawString(text, x + padX, y + padY + fm.getAscent());
}

private void applyDisplaySettingsFromMenu() {
    applyBooleanSetter(owner, "setFullscreenEnabled", gameMenu.isFullscreenEnabled());
    applyBooleanSetter(owner, "setFullscreen", gameMenu.isFullscreenEnabled());
    owner.revalidate();
    owner.repaint();
}

private void applyBooleanSetter(Object target, String methodName, boolean value) {
    try {
        java.lang.reflect.Method method = target.getClass().getMethod(methodName, boolean.class);
        method.invoke(target, value);
    } catch (ReflectiveOperationException ignored) {
    }
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
        if (!galaxyMapOpen && !gameMenuVisible) updateCameraControls(dt);
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
    long now = System.nanoTime();
    if (lastPaintNanos != 0L) {
        long delta = now - lastPaintNanos;
        if (delta > 0L) {
            currentFps = (int) Math.round(1_000_000_000.0 / delta);
        }
    }
    lastPaintNanos = now;

    long paintStarted = now;
    perfStats.frameStarted(paintStarted);
    super.paintComponent(g);

    Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    AffineTransform old = g2.getTransform();
    camera.apply(g2);
    world.draw(g2);
    if (devMode) aiDevOverlay.drawWorld(g2, world);
    drawSelectionBox(g2);
    g2.setTransform(old);

    WormholeIndicator.draw(g2, world, camera, getWidth(), getHeight());
    drawHud(g2);
    leaderboardHud.draw(g2, world, getWidth());
    hangarHud.draw(g2, world, getWidth());
    if (!galaxyMapOpen) minimapHud.draw(g2, world, camera, getWidth(), getHeight());
    if (world.devFreeBuild) shieldDebugOverlay.draw(g2, world, getWidth());
    if (devMode) { devMenu.draw(g2, world, canEditDev()); aiDevPanel.draw(g2, world, canEditDev()); }
    buildMenu.draw(g2);

    if (gameMenuVisible) {
        gameMenu.draw(g2, getWidth(), getHeight());
    }

    drawFpsOverlay(g2);

    if (galaxyMapOpen) galaxyMapOverlay.draw(g2, world.galaxyMapSnapshot(), getWidth(), getHeight());
    if (devMode && perfOverlayVisible) {
        PerfSnapshot networkStats = network == null ? null : network.perfSnapshot();
        PerfSnapshot hostStats = devAuthorityNetwork == null ? null : devAuthorityNetwork.perfSnapshot();
        perfOverlay.draw(g2, world, getWidth(), updateLabel(), perfStats.snapshot(), networkStats, hostStats);
    }

    g2.dispose();
    perfStats.recordDraw(System.nanoTime() - paintStarted);
}
    
    
    private String updateLabel() {
        if (network == null) return "Solo world update";
        if (devAuthorityNetwork != null || network.statusLine().startsWith("CLIENT")) return "Client prediction";
        return "World update";
    }

    private void drawHud(Graphics2D g2) {
        g2.setColor(new Color(0,0,0,175));
        g2.fillRoundRect(12, 12, 1120, 132, 14, 14);
        g2.setColor(Color.WHITE);
        String dev = world.devFreeBuild ? " | FREE CRAFTING" : "";
        g2.drawString("StarChem | " + PlayerRegistry.name(PlayerRegistry.localId()) + " | System: " + world.activeSystemId() + " | Selected: " + world.selectedCount() + dev, 28, 36);
        g2.setColor(new Color(210,230,245));
        g2.drawString(world.status, 28, 58);
        g2.drawString(network == null ? "Solo" : network.statusLine(), 28, 80);
        String minerRanges = UnitRenderer.miningRangeOverlayVisible() ? "ON" : "OFF";
        String audio = ProceduralAudio.muted() ? "OFF" : "ON";
        String perf = devMode ? " | Performance: F4" : "";
        g2.drawString("Galaxy: M | Inventory: I | Narration: F8 | Formation: " + formation.label + " (F) | Miner ranges: " + minerRanges + " (R) | Audio: " + audio + " (Ctrl+M)" + perf, 28, 102);
        g2.drawString("Commands: X attack-move | P patrol | G guard | E escort | H hold | Mode: " + commandModeLabel(), 28, 124);
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

    private Rectangle2D visibleWorldRect() { return screenRectToWorldRect(new Point(0, 0), new Point(getWidth(), getHeight())); }
    private boolean isSelectionDrag() { return dragStart != null && dragNow != null && dragStart.distance(dragNow) >= SELECT_DRAG_PX; }

    @Override public void mousePressed(MouseEvent e) {
    if (gameMenuVisible) {
        GameMenuOverlay.Selection selection = gameMenu.click(e.getX(), e.getY());
        applyDisplaySettingsFromMenu();

        switch (selection) {
            case RETURN -> {
                gameMenuVisible = false;
            }
            case MAIN_MENU -> {
                gameMenuVisible = false;
                owner.showLobby("Returned to main menu.");
            }
            case QUIT -> {
                System.exit(0);
            }
            default -> {}
        }

        repaint();
        return;
    }

    requestFocusInWindow();
    if (galaxyMapOpen) { clickGalaxyMap(e); return; }
    if (buildMenu.click(e.getX(), e.getY())) return;
    PeerNetwork devNetwork = devNetwork();
    if (devMode && aiDevPanel.click(world, devNetwork, e.getX(), e.getY(), canEditDev())) return;
    if (devMode && devMenu.click(world, devNetwork, e.getX(), e.getY(), canEditDev())) return;
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
    if (SwingUtilities.isRightMouseButton(e)) { clickRight(screenToWorld(e.getPoint())); return; }
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
        String targetSystemId = world.wormholeTargetAt(p.getX(), p.getY());
        if (targetSystemId != null && !targetSystemId.isBlank() && network != null) {
            network.viewSystem(network.localPlayerId(), targetSystemId);
            ProceduralAudio.play(SoundCue.SELECT);
            return;
        }
        if (world.jumpThroughWormholeAt(p.getX(), p.getY())) {
            ProceduralAudio.play(SoundCue.SELECT);
            return;
        }
        Base base = world.baseAt(p.getX(), p.getY());
        Unit unit = world.unitAt(p.getX(), p.getY());
        if (base != null) {
            ProceduralAudio.play(SoundCue.SELECT);
            if (PlayerRegistry.isLocal(base.playerId)) buildMenu.showForBase(world, network, base, e.getX(), e.getY());
            else world.status = "Enemy base: " + PlayerRegistry.name(base.playerId) + " | " + base.type().name + " | " + base.id;
            return;
        }
        if (unit != null && !PlayerRegistry.isLocal(unit.playerId)) { ProceduralAudio.play(SoundCue.SELECT); clearSelection(); world.status = "Enemy ship: " + PlayerRegistry.name(unit.playerId) + " | " + unit.type().name + " | " + unit.task; return; }
        if (unit != null && e.getClickCount() >= 2) { ProceduralAudio.play(SoundCue.SELECT); selectVisibleShipsOfSameType(unit); return; }
        if (unit != null && !unit.basePackageType.isBlank()) { ProceduralAudio.play(SoundCue.SELECT); clickPackageCarrier(e, p, unit); return; }
        world.selectAt(p.getX(), p.getY());
        if (world.status.startsWith("Selected ") || world.status.startsWith("Targeted ")) ProceduralAudio.play(SoundCue.SELECT);
    }

    private void selectVisibleShipsOfSameType(Unit clicked) {
        Rectangle2D view = visibleWorldRect();
        int selected = 0;
        for (Unit unit : world.units.values()) {
            boolean match = PlayerRegistry.isLocal(unit.playerId) && unit.shipTypeId.equals(clicked.shipTypeId) && view.contains(unit.x, unit.y);
            unit.selected = match;
            if (match) selected++;
        }
        world.selectedResourceId = -1;
        world.status = "Selected " + selected + " " + clicked.type().name + " ship(s) in view.";
    }

    private void clickPackageCarrier(MouseEvent e, Point2D p, Unit unit) { world.selectAt(p.getX(), p.getY()); buildMenu.showForUnit(world, network, unit, e.getX(), e.getY()); }

    private void clickRight(Point2D p) {
        if (commandMode != UnitOrderType.NONE) { handleCommandClick(p); return; }
        Unit enemyUnit = world.unitAt(p.getX(), p.getY());
        if (enemyUnit != null && !PlayerRegistry.isLocal(enemyUnit.playerId)) { ProceduralAudio.play(SoundCue.ATTACK_ORDER); orderAttack(CombatTarget.unit(enemyUnit)); return; }
        Base enemyBase = world.baseAt(p.getX(), p.getY());
        if (enemyBase != null && !PlayerRegistry.isLocal(enemyBase.playerId)) { ProceduralAudio.play(SoundCue.ATTACK_ORDER); orderAttack(CombatTarget.base(enemyBase)); return; }
        WormholeGate gate = wormholeAt(p);
        if (gate != null) {
            int selected = world.selectedCount();
            if (selected <= 0) {
                world.status = "No ship selected.";
                ProceduralAudio.play(SoundCue.ERROR);
                return;
            }
            for (Unit u : world.selectedUnits()) u.issueMove(gate.x, gate.y);
            world.status = "Moving " + selected + " ship(s) straight into wormhole to " + StarSystems.get(gate.toSystemId).name() + ".";
            ProceduralAudio.play(SoundCue.MOVE_ORDER);
            if (network != null) for (Unit u : world.selectedUnits()) if (PlayerRegistry.isLocal(u.playerId)) network.move(new MoveCommand(u.playerId, u.unitId, u.targetX, u.targetY));
            return;
        }
        ResourceNode node = world.resourceAt(p.getX(), p.getY());
        if (node != null) {
            world.autoHarvestSelected(node);
            ProceduralAudio.play(world.status.startsWith("Auto-harvesting ") ? SoundCue.HARVEST_ORDER : SoundCue.ERROR);
            if (network != null) for (Unit u : world.selectedUnits()) if (PlayerRegistry.isLocal(u.playerId) && u.automationResourceId == node.id) network.work(new HarvestCommand(u.playerId, u.unitId, node.id));
        } else {
            int selected = world.selectedCount();
            world.moveSelected(p.getX(), p.getY(), formation);
            ProceduralAudio.play(selected > 0 ? SoundCue.MOVE_ORDER : SoundCue.ERROR);
            if (network != null) for (Unit u : world.selectedUnits()) if (PlayerRegistry.isLocal(u.playerId)) network.move(new MoveCommand(u.playerId, u.unitId, u.targetX, u.targetY));
        }
    }

    private void handleCommandClick(Point2D p) {
        if (commandMode == UnitOrderType.PATROL && patrolStart == null) {
            patrolStart = p;
            world.status = "Patrol start set. Right-click the second patrol point.";
            ProceduralAudio.play(SoundCue.SELECT);
            return;
        }
        if (commandMode == UnitOrderType.PATROL) {
            issueSelectedOrder(UnitOrderType.PATROL, patrolStart.getX(), patrolStart.getY(), p.getX(), p.getY(), "");
            clearCommandMode();
            return;
        }
        if (commandMode == UnitOrderType.ATTACK_MOVE) {
            issueSelectedOrder(UnitOrderType.ATTACK_MOVE, p.getX(), p.getY(), p.getX(), p.getY(), "");
            clearCommandMode();
            return;
        }
        Unit unit = world.unitAt(p.getX(), p.getY());
        Base base = world.baseAt(p.getX(), p.getY());
        if (commandMode == UnitOrderType.ESCORT) {
            if (unit == null || !PlayerRegistry.isLocal(unit.playerId)) {
                world.status = "Escort requires a friendly ship target.";
                ProceduralAudio.play(SoundCue.ERROR);
                return;
            }
            issueSelectedOrder(UnitOrderType.ESCORT, unit.x, unit.y, unit.x, unit.y, CombatTarget.unit(unit));
            clearCommandMode();
            return;
        }
        if (commandMode == UnitOrderType.GUARD) {
            String target = "";
            if (unit != null && PlayerRegistry.isLocal(unit.playerId)) target = CombatTarget.unit(unit);
            else if (base != null && PlayerRegistry.isLocal(base.playerId)) target = CombatTarget.base(base);
            issueSelectedOrder(UnitOrderType.GUARD, p.getX(), p.getY(), p.getX(), p.getY(), target);
            clearCommandMode();
        }
    }

    private void issueSelectedOrder(UnitOrderType type, double x1, double y1, double x2, double y2, String targetKey) {
        List<Unit> selected = world.selectedUnits();
        world.orderSelected(type, x1, y1, x2, y2, targetKey, formation);
        int applied = 0;
        for (Unit unit : selected) {
            if (unit.orderType != type) continue;
            applied++;
            if (network != null) network.order(unit.orderCommand());
        }
        ProceduralAudio.play(applied > 0 ? SoundCue.MOVE_ORDER : SoundCue.ERROR);
    }

    private void setCommandMode(UnitOrderType type) {
        if (world.selectedCount() <= 0) {
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

    private void orderAttack(String targetKey) {
        world.attackSelected(targetKey);
        if (network != null) for (Unit u : world.selectedUnits()) if (PlayerRegistry.isLocal(u.playerId) && u.task == UnitTask.ATTACK && targetKey.equals(u.attackTarget)) network.attack(new AttackCommand(u.playerId, u.unitId, targetKey));
    }

    private void clearSelection() { for (Unit u : world.units.values()) u.selected = false; }
    private PeerNetwork devNetwork() { return devAuthorityNetwork != null ? devAuthorityNetwork : network; }
    private boolean canEditDev() {
        if (!devMode) return false;
        if (network == null) return true;
        PeerNetwork devNetwork = devNetwork();
        return devNetwork != null && devNetwork.devToolsAllowed();
    }

    @Override public void mouseDragged(MouseEvent e) {
        if (gameMenuVisible) return;
        if (galaxyMapOpen) return;
        if (dragStart != null) { dragNow = e.getPoint(); repaint(); return; }
        hangarHud.mouseDragged(e.getX(), e.getY(), getWidth(), getHeight());
        if (devMode) { aiDevPanel.drag(e.getX(), e.getY(), getWidth(), getHeight()); devMenu.drag(e.getX(), e.getY(), getWidth(), getHeight()); }
    }

    @Override public void mouseReleased(MouseEvent e) {
    if (gameMenuVisible) return;
    if (galaxyMapOpen) { dragStart = null; dragNow = null; return; }
    if (SwingUtilities.isLeftMouseButton(e) && dragStart != null) {
        dragNow = e.getPoint();
        if (isSelectionDrag()) { world.selectBox(screenRectToWorldRect(dragStart, dragNow)); if (world.selectedCount() > 0) ProceduralAudio.play(SoundCue.SELECT); }
        else clickLeft(e, screenToWorld(e.getPoint()));
        dragStart = null;
        dragNow = null;
        repaint();
    }
    hangarHud.mouseReleased();
    if (devMode) { aiDevPanel.release(); devMenu.release(); }
}

    @Override public void mouseWheelMoved(MouseWheelEvent e) {
    if (gameMenuVisible) {
        if (gameMenu.handleMouseWheel(e.getWheelRotation())) {
            repaint();
        }
        return;
    }

    if (!galaxyMapOpen && !minimapHud.bounds(world, getWidth(), getHeight()).contains(e.getPoint())) {
        camera.zoomAt(e.getPoint(), e.getWheelRotation(), world, getWidth(), getHeight());
    }
}

    @Override public void mouseMoved(MouseEvent e) {
    if (gameMenuVisible) {
        gameMenu.updateHover(e.getX(), e.getY());
        repaint();
        return;
    }
}

    @Override public void mouseClicked(MouseEvent e) { }
    @Override public void mouseEntered(MouseEvent e) { }
    @Override public void mouseExited(MouseEvent e) { }
    @Override public void keyTyped(KeyEvent e) { }

    @Override public void keyPressed(KeyEvent e) {
    if (gameMenuVisible) {
        if (gameMenu.handleKeyPressed(e)) {
            applyDisplaySettingsFromMenu();
            repaint();
            return;
        }

        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
            if (gameMenu.handleEscapePressed()) {
                repaint();
                return;
            }
            gameMenuVisible = false;
            repaint();
            return;
        }

        return;
    }

    if (gameMenu.matchesAction("mute_audio", e)) { boolean muted = ProceduralAudio.toggleMute(); world.status = muted ? "Audio muted." : "Audio enabled."; repaint(); return; }
    if (gameMenu.matchesAction("galaxy_map", e)) { toggleGalaxyMap(); return; }
    if (commandMode != UnitOrderType.NONE && e.getKeyCode() == KeyEvent.VK_ESCAPE) { clearCommandMode(); world.status = "Command mode cancelled."; repaint(); return; }
    if (galaxyMapOpen && e.getKeyCode() == KeyEvent.VK_ESCAPE) { closeGalaxyMap(); return; }

    if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
        gameMenuVisible = true;
        clearCameraKeys();
        clearCommandMode();
        dragStart = null;
        dragNow = null;
        repaint();
        return;
    }

    if (galaxyMapOpen) return;

    if (devMode && gameMenu.matchesAction("ai_debug_overlay", e)) { AiDevSettings.overlay = !AiDevSettings.overlay; world.status = "AI debug overlay: " + (AiDevSettings.overlay ? "ON" : "OFF") + "."; repaint(); return; }
    if (devMode && gameMenu.matchesAction("performance_overlay", e)) { perfOverlayVisible = !perfOverlayVisible; world.status = "Performance overlay: " + (perfOverlayVisible ? "ON" : "OFF") + "."; repaint(); return; }

    if (gameMenu.matchesAction("fleet_formation", e)) { formation = formation.next(); world.status = "Fleet formation: " + formation.label + "."; ProceduralAudio.play(SoundCue.SELECT); return; }
    if (gameMenu.matchesAction("miner_range_overlay", e)) { UnitRenderer.toggleMiningRangeOverlay(); world.status = "Miner range overlay: " + (UnitRenderer.miningRangeOverlayVisible() ? "ON" : "OFF") + "."; ProceduralAudio.play(SoundCue.SELECT); repaint(); return; }
    if (gameMenu.matchesAction("attack_move", e)) { setCommandMode(UnitOrderType.ATTACK_MOVE); return; }
    if (gameMenu.matchesAction("patrol", e)) { setCommandMode(UnitOrderType.PATROL); return; }
    if (gameMenu.matchesAction("guard", e)) { setCommandMode(UnitOrderType.GUARD); return; }
    if (gameMenu.matchesAction("escort", e)) { setCommandMode(UnitOrderType.ESCORT); return; }
    if (gameMenu.matchesAction("hold", e)) { issueSelectedOrder(UnitOrderType.HOLD, 0, 0, 0, 0, ""); clearCommandMode(); return; }

    setCameraKeyFromBindings(e, true);
}

    @Override public void keyReleased(KeyEvent e) {
    if (gameMenuVisible) {
        return;
    }
    setCameraKeyFromBindings(e, false);
}

    @Override public void focusGained(FocusEvent e) { }
    @Override public void focusLost(FocusEvent e) {
        clearCameraKeys();
        clearCommandMode();
        dragStart = null;
        dragNow = null;
    }

    private void toggleGalaxyMap() {
        galaxyMapOpen = !galaxyMapOpen;
        clearCommandMode();
        dragStart = null;
        dragNow = null;
        clearCameraKeys();
        world.status = galaxyMapOpen ? "Galaxy map open. Click a system to travel/view it." : "Galaxy map closed.";
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

    private void clearCameraKeys() { cameraLeft = cameraRight = cameraUp = cameraDown = false; }
    private void setCameraKey(int code) { setCameraKey(code, true); }
    private void setCameraKey(int code, boolean down) {
        if (code == KeyEvent.VK_A || code == KeyEvent.VK_LEFT) cameraLeft = down;
        if (code == KeyEvent.VK_D || code == KeyEvent.VK_RIGHT) cameraRight = down;
        if (code == KeyEvent.VK_W || code == KeyEvent.VK_UP) cameraUp = down;
        if (code == KeyEvent.VK_S || code == KeyEvent.VK_DOWN) cameraDown = down;
    }
}
