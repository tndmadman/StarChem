package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;

final class GamePanel extends JPanel implements KeyListener, MouseListener, MouseMotionListener, MouseWheelListener {
    private static final double CAMERA_PAN_SPEED = 640.0;
    private static final int SELECT_DRAG_PX = 7;
    private final World world;
    private final GameFrame owner;
    private final PeerNetwork network;
    private final Timer timer;
    private final BuildMenu buildMenu = new BuildMenu();
    private final GameCamera camera = new GameCamera();
    private final HangarHud hangarHud = new HangarHud();
    private final LeaderboardHud leaderboardHud = new LeaderboardHud();
    private final ShieldDebugOverlay shieldDebugOverlay = new ShieldDebugOverlay();
    private FleetFormation formation = FleetFormation.GRID;
    private Point dragStart;
    private Point dragNow;
    private long lastNanos = System.nanoTime();
    private boolean cameraLeft, cameraRight, cameraUp, cameraDown;

    GamePanel(World world, GameFrame owner) { this(world, owner, null, false); }
    GamePanel(World world, GameFrame owner, PeerNetwork network) { this(world, owner, network, false); }

    GamePanel(World world, GameFrame owner, PeerNetwork network, boolean devMode) {
        this.world = world;
        this.owner = owner;
        this.network = network;
        setFocusable(true);
        setBackground(new Color(8, 12, 18));
        addKeyListener(this);
        addMouseListener(this);
        addMouseMotionListener(this);
        addMouseWheelListener(this);
        timer = new Timer(16, e -> tick());
    }

    void start() { requestFocusInWindow(); timer.start(); }
    void stop() { timer.stop(); }

    private void tick() {
        long now = System.nanoTime();
        double dt = Math.min(0.05, (now - lastNanos) / 1_000_000_000.0);
        lastNanos = now;
        boolean hostOrSolo = network == null || network.statusLine().startsWith("HOST");
        if (hostOrSolo) world.update(dt);
        else {
            world.updateEnvironment(dt);
            ClientPrediction.update(world, dt);
        }
        updateCameraControls(dt);
        camera.update(world, getWidth(), getHeight(), dt);
        repaint();
    }

    private void updateCameraControls(double dt) {
        double dx = 0;
        double dy = 0;
        double step = CAMERA_PAN_SPEED * dt;
        if (cameraLeft) dx -= step;
        if (cameraRight) dx += step;
        if (cameraUp) dy -= step;
        if (cameraDown) dy += step;
        if (dx != 0 || dy != 0) camera.panByScreen(dx, dy, world, getWidth(), getHeight());
    }

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        AffineTransform old = g2.getTransform();
        camera.apply(g2);
        world.draw(g2);
        drawSelectionBox(g2);
        g2.setTransform(old);
        drawHud(g2);
        leaderboardHud.draw(g2, world, getWidth());
        hangarHud.draw(g2, world, getWidth());
        if (world.devFreeBuild) shieldDebugOverlay.draw(g2, world, getWidth());
        buildMenu.draw(g2);
        g2.dispose();
    }

    private void drawHud(Graphics2D g2) {
        g2.setColor(new Color(0,0,0,175));
        g2.fillRoundRect(12, 12, 980, 112, 14, 14);
        g2.setColor(Color.WHITE);
        String dev = world.devFreeBuild ? " | DEV FREE BUILD" : "";
        g2.drawString("StarChem | " + PlayerRegistry.name(PlayerRegistry.localId()) + " | Selected: " + world.selectedCount() + dev, 28, 36);
        g2.setColor(new Color(210,230,245));
        g2.drawString(world.status, 28, 58);
        g2.drawString(network == null ? "Solo" : network.statusLine(), 28, 80);
        String minerRanges = UnitRenderer.miningRangeOverlayVisible() ? "ON" : "OFF";
        g2.drawString("Drag-select ships | Right-click group order | Formation: " + formation.label + " (F) | Miner ranges: " + minerRanges + " (R)", 28, 102);
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
        Point2D aw = screenToWorld(a);
        Point2D bw = screenToWorld(b);
        double x = Math.min(aw.getX(), bw.getX());
        double y = Math.min(aw.getY(), bw.getY());
        double w = Math.abs(aw.getX() - bw.getX());
        double h = Math.abs(aw.getY() - bw.getY());
        return new Rectangle2D.Double(x, y, w, h);
    }

    private boolean isSelectionDrag() {
        if (dragStart == null || dragNow == null) return false;
        return dragStart.distance(dragNow) >= SELECT_DRAG_PX;
    }

    @Override public void mousePressed(MouseEvent e) {
        requestFocusInWindow();
        if (buildMenu.click(e.getX(), e.getY())) return;
        if (hangarHud.mousePressed(world, e.getX(), e.getY())) return;
        if (SwingUtilities.isRightMouseButton(e)) {
            clickRight(screenToWorld(e.getPoint()));
            return;
        }
        if (SwingUtilities.isLeftMouseButton(e)) {
            dragStart = e.getPoint();
            dragNow = e.getPoint();
        }
    }

    private void clickLeft(MouseEvent e, Point2D p) {
        Base base = world.baseAt(p.getX(), p.getY());
        Unit unit = world.unitAt(p.getX(), p.getY());
        if (base != null) {
            if (PlayerRegistry.isLocal(base.playerId)) buildMenu.showForBase(world, network, base, e.getX(), e.getY());
            else world.status = "Enemy base: " + PlayerRegistry.name(base.playerId);
            return;
        }
        if (unit != null && !PlayerRegistry.isLocal(unit.playerId)) {
            clearSelection();
            world.status = "Enemy ship: " + PlayerRegistry.name(unit.playerId);
            return;
        }
        if (unit != null && !unit.basePackageType.isBlank()) {
            clickPackageCarrier(e, p, unit);
            return;
        }
        world.selectAt(p.getX(), p.getY());
    }

    private void clickPackageCarrier(MouseEvent e, Point2D p, Unit unit) {
        world.selectAt(p.getX(), p.getY());
        buildMenu.showForUnit(world, network, unit, e.getX(), e.getY());
    }

    private void clickRight(Point2D p) {
        Unit enemyUnit = world.unitAt(p.getX(), p.getY());
        if (enemyUnit != null && !PlayerRegistry.isLocal(enemyUnit.playerId)) {
            orderAttack(CombatTarget.unit(enemyUnit));
            return;
        }
        Base enemyBase = world.baseAt(p.getX(), p.getY());
        if (enemyBase != null && !PlayerRegistry.isLocal(enemyBase.playerId)) {
            orderAttack(CombatTarget.base(enemyBase));
            return;
        }
        ResourceNode node = world.resourceAt(p.getX(), p.getY());
        if (node != null) {
            world.autoHarvestSelected(node);
            if (network != null) for (Unit u : world.selectedUnits()) if (PlayerRegistry.isLocal(u.playerId) && u.automationResourceId == node.id) network.work(new HarvestCommand(u.playerId, u.unitId, node.id));
        } else {
            world.moveSelected(p.getX(), p.getY(), formation);
            if (network != null) for (Unit u : world.selectedUnits()) if (PlayerRegistry.isLocal(u.playerId)) network.move(new MoveCommand(u.playerId, u.unitId, u.targetX, u.targetY));
        }
    }

    private void orderAttack(String targetKey) {
        world.attackSelected(targetKey);
        if (network != null) for (Unit u : world.selectedUnits()) {
            if (PlayerRegistry.isLocal(u.playerId) && u.task == UnitTask.ATTACK && targetKey.equals(u.attackTarget)) {
                network.attack(new AttackCommand(u.playerId, u.unitId, targetKey));
            }
        }
    }

    private void clearSelection() { for (Unit u : world.units.values()) u.selected = false; }

    @Override public void mouseDragged(MouseEvent e) {
        if (dragStart != null) {
            dragNow = e.getPoint();
            repaint();
            return;
        }
        hangarHud.mouseDragged(e.getX(), e.getY(), getWidth(), getHeight());
    }

    @Override public void mouseReleased(MouseEvent e) {
        if (SwingUtilities.isLeftMouseButton(e) && dragStart != null) {
            dragNow = e.getPoint();
            if (isSelectionDrag()) world.selectBox(screenRectToWorldRect(dragStart, dragNow));
            else clickLeft(e, screenToWorld(e.getPoint()));
            dragStart = null;
            dragNow = null;
            repaint();
        }
        hangarHud.mouseReleased();
    }

    @Override public void mouseWheelMoved(MouseWheelEvent e) { camera.zoomAt(e.getPoint(), e.getWheelRotation(), world, getWidth(), getHeight()); }
    @Override public void mouseMoved(MouseEvent e) { }
    @Override public void mouseClicked(MouseEvent e) { }
    @Override public void mouseEntered(MouseEvent e) { }
    @Override public void mouseExited(MouseEvent e) { }
    @Override public void keyTyped(KeyEvent e) { }
    @Override public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_F) {
            formation = formation.next();
            world.status = "Fleet formation: " + formation.label + ".";
            return;
        }
        if (e.getKeyCode() == KeyEvent.VK_R) {
            UnitRenderer.toggleMiningRangeOverlay();
            world.status = "Miner range overlay: " + (UnitRenderer.miningRangeOverlayVisible() ? "ON" : "OFF") + ".";
            repaint();
            return;
        }
        setCameraKey(e.getKeyCode(), true);
    }
    @Override public void keyReleased(KeyEvent e) { setCameraKey(e.getKeyCode(), false); }

    private void setCameraKey(int code, boolean down) {
        if (code == KeyEvent.VK_A || code == KeyEvent.VK_LEFT) cameraLeft = down;
        if (code == KeyEvent.VK_D || code == KeyEvent.VK_RIGHT) cameraRight = down;
        if (code == KeyEvent.VK_W || code == KeyEvent.VK_UP) cameraUp = down;
        if (code == KeyEvent.VK_S || code == KeyEvent.VK_DOWN) cameraDown = down;
    }
}