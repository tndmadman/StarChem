package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;

final class GamePanel extends JPanel implements KeyListener, MouseListener, MouseMotionListener, MouseWheelListener {
    private static final double CAMERA_PAN_SPEED = 640.0;
    private final World world;
    private final GameFrame owner;
    private final PeerNetwork network;
    private final boolean devMode;
    private final Timer timer;
    private final BuildMenu buildMenu = new BuildMenu();
    private final GameCamera camera = new GameCamera();
    private final HangarHud hangarHud = new HangarHud();
    private final DevMenu devMenu = new DevMenu();
    private long lastNanos = System.nanoTime();
    private boolean cameraLeft, cameraRight, cameraUp, cameraDown;

    GamePanel(World world, GameFrame owner) { this(world, owner, null, false); }
    GamePanel(World world, GameFrame owner, PeerNetwork network) { this(world, owner, network, false); }

    GamePanel(World world, GameFrame owner, PeerNetwork network, boolean devMode) {
        this.world = world;
        this.owner = owner;
        this.network = network;
        this.devMode = devMode;
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
        else ClientPrediction.update(world, dt);
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
        g2.setTransform(old);
        drawHud(g2);
        hangarHud.draw(g2, world, getWidth());
        if (devMode) devMenu.draw(g2, world, canEditDev());
        buildMenu.draw(g2);
        g2.dispose();
    }

    private void drawHud(Graphics2D g2) {
        g2.setColor(new Color(0,0,0,175));
        g2.fillRoundRect(12, 12, 980, 92, 14, 14);
        g2.setColor(Color.WHITE);
        g2.drawString("StarChem | " + PlayerRegistry.name(PlayerRegistry.localId()) + " | Selected: " + world.selectedCount(), 28, 36);
        g2.setColor(new Color(210,230,245));
        g2.drawString(world.status, 28, 58);
        g2.drawString(network == null ? "Solo" : network.statusLine(), 28, 80);
    }

    private boolean canEditDev() { return network == null || network.statusLine().startsWith("HOST"); }
    private Point2D screenToWorld(Point p) { return camera.screenToWorld(p); }

    @Override public void mousePressed(MouseEvent e) {
        requestFocusInWindow();
        if (buildMenu.click(e.getX(), e.getY())) return;
        if (devMode && devMenu.click(world, e.getX(), e.getY(), canEditDev())) return;
        if (hangarHud.mousePressed(world, e.getX(), e.getY())) return;
        Point2D p = screenToWorld(e.getPoint());
        if (SwingUtilities.isLeftMouseButton(e)) clickLeft(e, p);
        else if (SwingUtilities.isRightMouseButton(e)) clickRight(p);
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
        if (unit != null && !unit.basePackageType.isBlank()) buildMenu.showForUnit(world, network, unit, e.getX(), e.getY());
        else world.selectAt(p.getX(), p.getY());
    }

    private void clickRight(Point2D p) {
        ResourceNode node = world.resourceAt(p.getX(), p.getY());
        if (node != null) {
            world.autoHarvestSelected(node);
            if (network != null) for (Unit u : world.selectedUnits()) if (PlayerRegistry.isLocal(u.playerId) && u.automationResourceId == node.id) network.work(new HarvestCommand(u.playerId, u.unitId, node.id));
        } else {
            world.moveSelected(p.getX(), p.getY());
            if (network != null) for (Unit u : world.selectedUnits()) if (PlayerRegistry.isLocal(u.playerId)) network.move(new MoveCommand(u.playerId, u.unitId, u.targetX, u.targetY));
        }
    }

    private void clearSelection() { for (Unit u : world.units.values()) u.selected = false; }

    @Override public void mouseDragged(MouseEvent e) {
        hangarHud.mouseDragged(e.getX(), e.getY(), getWidth(), getHeight());
        if (devMode) devMenu.drag(e.getX(), e.getY(), getWidth(), getHeight());
    }

    @Override public void mouseReleased(MouseEvent e) {
        hangarHud.mouseReleased();
        if (devMode) devMenu.release();
    }

    @Override public void mouseWheelMoved(MouseWheelEvent e) {
        camera.zoomAt(e.getPoint(), e.getWheelRotation(), world, getWidth(), getHeight());
    }

    @Override public void keyPressed(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_ESCAPE -> owner.showLobby("Returned to lobby.");
            case KeyEvent.VK_A -> cameraLeft = true;
            case KeyEvent.VK_D -> cameraRight = true;
            case KeyEvent.VK_W -> cameraUp = true;
            case KeyEvent.VK_S -> cameraDown = true;
            default -> { }
        }
    }

    @Override public void keyTyped(KeyEvent e) { }

    @Override public void keyReleased(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_A -> cameraLeft = false;
            case KeyEvent.VK_D -> cameraRight = false;
            case KeyEvent.VK_W -> cameraUp = false;
            case KeyEvent.VK_S -> cameraDown = false;
            default -> { }
        }
    }

    @Override public void mouseMoved(MouseEvent e) { }
    @Override public void mouseClicked(MouseEvent e) { }
    @Override public void mouseEntered(MouseEvent e) { }
    @Override public void mouseExited(MouseEvent e) { }
}
